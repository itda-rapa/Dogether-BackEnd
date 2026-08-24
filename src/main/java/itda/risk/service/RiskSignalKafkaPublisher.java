package itda.risk.service;

import itda.risk.contract.RiskTopic;
import itda.risk.service.RiskSignalOutboxClaimService.ClaimedRiskSignal;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class RiskSignalKafkaPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;

    public RiskSignalKafkaPublisher(
            @Qualifier("riskKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate
    ) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(ClaimedRiskSignal event, Duration timeout) {
        try {
            kafkaTemplate.send(
                    RiskTopic.RISK_SIGNAL_TOPIC,
                    Long.toString(event.actorUserId()),
                    event.payload()
            ).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RiskSignalPublishException("Kafka publish interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new RiskSignalPublishException("Kafka publish failed", exception);
        } catch (RuntimeException exception) {
            throw new RiskSignalPublishException("Kafka publish failed", exception);
        }
    }

    public static class RiskSignalPublishException extends RuntimeException {
        RiskSignalPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
