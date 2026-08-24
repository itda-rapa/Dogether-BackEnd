package itda.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.risk.service.RiskSignalKafkaPublisher.RiskSignalPublishException;
import itda.risk.service.RiskSignalOutboxClaimService.ClaimedRiskSignal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RiskSignalOutboxRelayWorkerTest {
    private static final Instant NOW = Instant.parse("2026-08-24T01:00:00Z");

    private final RiskSignalOutboxClaimService claims = mock(RiskSignalOutboxClaimService.class);
    private final RiskSignalKafkaPublisher publisher = mock(RiskSignalKafkaPublisher.class);
    private final RiskSignalOutboxRelayMetrics metrics = mock(RiskSignalOutboxRelayMetrics.class);
    private final RiskSignalOutboxRelayProperties properties = properties(2, 3);
    private final RiskSignalOutboxRelayWorker worker = new RiskSignalOutboxRelayWorker(
            claims, publisher, metrics, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acknowledgedEventBecomesSent() {
        ClaimedRiskSignal event = RiskSignalKafkaPublisherTest.event(1);
        when(claims.claim(1, properties.lease())).thenReturn(List.of(event), List.of());
        when(claims.markSent(event)).thenReturn(true);

        assertThat(worker.runOnce()).isEqualTo(new RiskSignalOutboxRelayWorker.Result(1, 0, 0, 0));

        verify(publisher).publish(event, properties.sendTimeout());
        verify(claims).markSent(event);
        verify(metrics).recordResult(event, "sent");
        verify(metrics).recordDeliveryDelay(Duration.between(event.occurredAt(), NOW));
    }

    @Test
    void failedPublishSchedulesExponentialRetry() {
        ClaimedRiskSignal event = RiskSignalKafkaPublisherTest.event(1);
        when(claims.claim(1, properties.lease())).thenReturn(List.of(event), List.of());
        doThrow(failure()).when(publisher).publish(event, properties.sendTimeout());
        when(claims.retry(eq(event), any(Instant.class), any(String.class))).thenReturn(true);

        assertThat(worker.runOnce()).isEqualTo(new RiskSignalOutboxRelayWorker.Result(0, 1, 0, 0));

        ArgumentCaptor<Instant> retryAt = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(claims).retry(eq(event), retryAt.capture(), error.capture());
        assertThat(retryAt.getValue()).isEqualTo(NOW.plusMillis(5_001));
        assertThat(error.getValue()).isEqualTo("kafka publish failed: TimeoutException");
        verify(metrics).recordResult(event, "retried");
    }

    @Test
    void lastFailedAttemptBecomesTerminal() {
        ClaimedRiskSignal event = RiskSignalKafkaPublisherTest.event(3);
        when(claims.claim(1, properties.lease())).thenReturn(List.of(event), List.of());
        doThrow(failure()).when(publisher).publish(event, properties.sendTimeout());
        when(claims.fail(event, "kafka publish failed: TimeoutException")).thenReturn(true);

        assertThat(worker.runOnce()).isEqualTo(new RiskSignalOutboxRelayWorker.Result(0, 0, 1, 0));

        verify(claims).fail(event, "kafka publish failed: TimeoutException");
        verify(claims, never()).retry(any(), any(), any());
        verify(metrics).recordResult(event, "failed");
    }

    @Test
    void staleCrashBeyondAttemptLimitFailsWithoutAnotherPublish() {
        ClaimedRiskSignal event = RiskSignalKafkaPublisherTest.event(4);
        when(claims.claim(1, properties.lease())).thenReturn(List.of(event), List.of());
        when(claims.fail(event, "retry limit exceeded after stale recovery")).thenReturn(true);

        assertThat(worker.runOnce()).isEqualTo(new RiskSignalOutboxRelayWorker.Result(0, 0, 1, 0));

        verify(publisher, never()).publish(any(), any());
        verify(claims).fail(event, "retry limit exceeded after stale recovery");
    }

    @Test
    void oldClaimCannotReportSent() {
        ClaimedRiskSignal event = RiskSignalKafkaPublisherTest.event(1);
        when(claims.claim(1, properties.lease())).thenReturn(List.of(event), List.of());
        when(claims.markSent(event)).thenReturn(false);

        assertThat(worker.runOnce()).isEqualTo(new RiskSignalOutboxRelayWorker.Result(0, 0, 0, 1));

        verify(metrics, never()).recordResult(event, "sent");
        verify(metrics).recordResult(event, "fenced");
    }

    @Test
    void claimsOneAtATimeUpToConfiguredCycleLimit() {
        ClaimedRiskSignal first = RiskSignalKafkaPublisherTest.event(1);
        ClaimedRiskSignal second = new ClaimedRiskSignal(
                2L, first.eventId(), first.sourceType(), 11L, first.signalType(), first.actorUserId(),
                first.occurredAt(), first.payload(), 1, java.util.UUID.randomUUID());
        when(claims.claim(1, properties.lease()))
                .thenReturn(List.of(first), List.of(second));
        when(claims.markSent(any())).thenReturn(true);

        assertThat(worker.runOnce().sent()).isEqualTo(2);

        verify(claims, org.mockito.Mockito.times(2)).claim(1, properties.lease());
        verify(claims, never()).claim(properties.batchSize(), properties.lease());
    }

    @Test
    void interruptStopsCycleAfterCurrentEventStateIsRecorded() {
        ClaimedRiskSignal first = RiskSignalKafkaPublisherTest.event(1);
        when(claims.claim(1, properties.lease())).thenReturn(List.of(first));
        doThrow(new RiskSignalPublishException("interrupted", new InterruptedException()))
                .when(publisher).publish(first, properties.sendTimeout());
        when(claims.retry(eq(first), any(Instant.class), any(String.class))).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return true;
        });
        try {
            assertThat(worker.runOnce()).isEqualTo(new RiskSignalOutboxRelayWorker.Result(0, 1, 0, 0));
            verify(claims).claim(1, properties.lease());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void failedPublishStateUpdateExceptionIsObservedAndLeaseRecoveryIsPreserved() {
        ClaimedRiskSignal event = RiskSignalKafkaPublisherTest.event(1);
        when(claims.claim(1, properties.lease())).thenReturn(List.of(event), List.of());
        doThrow(failure()).when(publisher).publish(event, properties.sendTimeout());
        when(claims.retry(eq(event), any(Instant.class), any(String.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThat(worker.runOnce()).isEqualTo(new RiskSignalOutboxRelayWorker.Result(0, 0, 0, 1));

        verify(metrics).recordResult(event, "state_unknown");
        verify(metrics, never()).recordResult(event, "retried");
    }

    @Test
    void acknowledgedPublishStateUpdateExceptionIsObservedForAtLeastOnceRecovery() {
        ClaimedRiskSignal event = RiskSignalKafkaPublisherTest.event(1);
        when(claims.claim(1, properties.lease())).thenReturn(List.of(event), List.of());
        when(claims.markSent(event)).thenThrow(new IllegalStateException("database unavailable"));

        assertThat(worker.runOnce()).isEqualTo(new RiskSignalOutboxRelayWorker.Result(0, 0, 0, 1));

        verify(metrics).recordResult(event, "state_unknown");
    }

    private static RiskSignalPublishException failure() {
        return new RiskSignalPublishException("failed", new TimeoutException());
    }

    private static RiskSignalOutboxRelayProperties properties(int batchSize, int maxAttempts) {
        return new RiskSignalOutboxRelayProperties(
                true, 1_000, batchSize, Duration.ofMinutes(1), Duration.ofSeconds(10),
                Duration.ofSeconds(5), maxAttempts, Duration.ofSeconds(5), Duration.ofMinutes(10));
    }
}
