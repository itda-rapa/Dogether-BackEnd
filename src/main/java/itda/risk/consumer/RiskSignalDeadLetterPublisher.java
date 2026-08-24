package itda.risk.consumer;

import itda.risk.config.RiskSignalConsumerProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class RiskSignalDeadLetterPublisher implements ConsumerRecordRecoverer {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final RiskSignalConsumerProperties properties;
    private final RiskSignalConsumerMetrics metrics;

    public RiskSignalDeadLetterPublisher(
            @Qualifier("riskKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            RiskSignalConsumerProperties properties,
            RiskSignalConsumerMetrics metrics
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        boolean contractFailure = RiskSignalConsumerMetrics.hasCause(
                exception, RiskSignalContractException.class);
        String reason = contractFailure ? "CONTRACT" : "RETRY_EXHAUSTED";

        try {
            String value = contractFailure
                    ? sanitizedEnvelope(record, reason) : String.valueOf(record.value());
            String key = contractFailure ? null : stringKey(record.key());
            Integer partition = contractFailure ? null : record.partition();
            ProducerRecord<String, String> dltRecord = new ProducerRecord<>(
                    properties.dltTopic(), partition, key, value, dltHeaders(record, reason));
            kafkaTemplate.send(dltRecord).get(
                    properties.dltPublishTimeout().toMillis(), TimeUnit.MILLISECONDS);
            metrics.recordDlt(reason, true);
            log.warn("Risk signal moved to DLT: topic={}, partition={}, offset={}, reason={}",
                    record.topic(), record.partition(), record.offset(), reason);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            metrics.recordDlt(reason, false);
            log.warn("Risk signal DLT publish interrupted: topic={}, partition={}, offset={}, reason={}",
                    record.topic(), record.partition(), record.offset(), reason);
            throw new RiskSignalDltPublishException(interruptedException);
        } catch (Exception publishException) {
            metrics.recordDlt(reason, false);
            log.warn("Risk signal DLT publish failed: topic={}, partition={}, offset={}, reason={}, type={}",
                    record.topic(), record.partition(), record.offset(), reason,
                    publishException.getClass().getSimpleName());
            throw new RiskSignalDltPublishException(publishException);
        }
    }

    private static String stringKey(Object key) {
        return key instanceof String string ? string : null;
    }

    private static RecordHeaders dltHeaders(ConsumerRecord<?, ?> record, String reason) {
        RecordHeaders headers = new RecordHeaders();
        headers.add("dogether-dlt-source-topic", utf8(record.topic()));
        headers.add("dogether-dlt-source-partition", utf8(Integer.toString(record.partition())));
        headers.add("dogether-dlt-source-offset", utf8(Long.toString(record.offset())));
        headers.add("dogether-dlt-reason", utf8(reason));
        return headers;
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String sanitizedEnvelope(ConsumerRecord<?, ?> record, String reason) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("sourceTopic", record.topic());
            envelope.put("sourcePartition", record.partition());
            envelope.put("sourceOffset", record.offset());
            envelope.put("reason", reason);
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception exception) {
            throw new RiskSignalDltPublishException(exception);
        }
    }

    static class RiskSignalDltPublishException extends RuntimeException {
        RiskSignalDltPublishException(Throwable cause) {
            super("Failed to publish a risk signal DLT record", cause);
        }
    }
}
