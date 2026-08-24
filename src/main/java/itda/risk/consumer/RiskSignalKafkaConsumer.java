package itda.risk.consumer;

import itda.risk.contract.RiskSignalEventV1;
import itda.risk.contract.RiskTopic;
import itda.risk.service.RiskSignalIngestionService;
import itda.risk.service.RiskSignalIngestionService.Result;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.risk.consumer", name = "enabled", havingValue = "true")
public class RiskSignalKafkaConsumer {
    private final RiskSignalEventDecoder decoder;
    private final RiskSignalIngestionService ingestionService;
    private final RiskSignalConsumerMetrics metrics;

    @KafkaListener(
            topics = RiskTopic.RISK_SIGNAL_TOPIC,
            groupId = "${app.risk.consumer.group-id}",
            containerFactory = "riskKafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        Instant startedAt = Instant.now();
        try {
            RiskSignalEventV1 event = decoder.decode(record.key(), record.value());
            Result result = ingestionService.ingest(event);
            acknowledgment.acknowledge();
            metrics.recordConsumed(event, result, Duration.between(startedAt, Instant.now()));
            log.debug("Risk signal consumed: eventId={}, signal={}, result={}",
                    event.eventId(), event.signalType(), result);
        } catch (RiskSignalContractException exception) {
            metrics.recordContractFailure(exception.reason());
            log.warn("Invalid risk signal: topic={}, partition={}, offset={}, reason={}",
                    record.topic(), record.partition(), record.offset(), exception.reason());
            throw exception;
        }
    }
}
