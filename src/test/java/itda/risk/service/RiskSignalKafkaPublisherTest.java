package itda.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.risk.contract.RiskSignalType;
import itda.risk.contract.RiskSourceType;
import itda.risk.contract.RiskTopic;
import itda.risk.service.RiskSignalKafkaPublisher.RiskSignalPublishException;
import itda.risk.service.RiskSignalOutboxClaimService.ClaimedRiskSignal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class RiskSignalKafkaPublisherTest {
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final RiskSignalKafkaPublisher publisher = new RiskSignalKafkaPublisher(kafkaTemplate);

    @Test
    void publishesOutboxPayloadWithoutReconstruction() {
        ClaimedRiskSignal event = event(1);
        CompletableFuture<SendResult<String, String>> completed =
                CompletableFuture.completedFuture(mock(SendResult.class));
        when(kafkaTemplate.send(RiskTopic.RISK_SIGNAL_TOPIC, "41", event.payload()))
                .thenReturn(completed);

        publisher.publish(event, Duration.ofSeconds(1));

        verify(kafkaTemplate).send(RiskTopic.RISK_SIGNAL_TOPIC, "41", event.payload());
    }

    @Test
    void detectsAsynchronousBrokerFailure() {
        ClaimedRiskSignal event = event(1);
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafkaTemplate.send(RiskTopic.RISK_SIGNAL_TOPIC, "41", event.payload()))
                .thenReturn(failed);

        assertThatThrownBy(() -> publisher.publish(event, Duration.ofSeconds(1)))
                .isInstanceOf(RiskSignalPublishException.class)
                .hasCauseInstanceOf(java.util.concurrent.ExecutionException.class);
    }

    @Test
    void timesOutAnUnacknowledgedSend() {
        ClaimedRiskSignal event = event(1);
        when(kafkaTemplate.send(RiskTopic.RISK_SIGNAL_TOPIC, "41", event.payload()))
                .thenReturn(new CompletableFuture<>());

        assertThatThrownBy(() -> publisher.publish(event, Duration.ofMillis(1)))
                .isInstanceOf(RiskSignalPublishException.class)
                .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
    }

    @Test
    void restoresInterruptFlag() {
        ClaimedRiskSignal event = event(1);
        when(kafkaTemplate.send(RiskTopic.RISK_SIGNAL_TOPIC, "41", event.payload()))
                .thenReturn(new CompletableFuture<>());
        try {
            Thread.currentThread().interrupt();

            assertThatThrownBy(() -> publisher.publish(event, Duration.ofSeconds(1)))
                    .isInstanceOf(RiskSignalPublishException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    static ClaimedRiskSignal event(int attempts) {
        return new ClaimedRiskSignal(
                1L,
                UUID.fromString("99773a4b-9e5f-4180-afef-a840ed09ec99"),
                RiskSourceType.USER_BLOCK,
                10L,
                RiskSignalType.USER_BLOCKED,
                41L,
                Instant.parse("2026-08-24T00:00:00Z"),
                "{\"schemaVersion\":1,\"eventId\":\"99773a4b-9e5f-4180-afef-a840ed09ec99\"}",
                attempts,
                UUID.fromString("6573270e-8e04-4bdd-981e-0ab87af3a653")
        );
    }
}
