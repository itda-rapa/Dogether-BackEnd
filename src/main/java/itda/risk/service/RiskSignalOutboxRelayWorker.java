package itda.risk.service;

import itda.risk.service.RiskSignalKafkaPublisher.RiskSignalPublishException;
import itda.risk.service.RiskSignalOutboxClaimService.ClaimedRiskSignal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RiskSignalOutboxRelayWorker {
    private final RiskSignalOutboxClaimService claims;
    private final RiskSignalKafkaPublisher publisher;
    private final RiskSignalOutboxRelayMetrics metrics;
    private final RiskSignalOutboxRelayProperties properties;
    private final Clock clock;

    public Result runOnce() {
        int sent = 0;
        int retried = 0;
        int failed = 0;
        int fenced = 0;

        for (int processed = 0; processed < properties.batchSize(); processed++) {
            List<ClaimedRiskSignal> claimed = claims.claim(1, properties.lease());
            if (claimed.isEmpty()) {
                break;
            }
            ClaimedRiskSignal event = claimed.getFirst();
            if (event.attempts() > properties.maxAttempts()) {
                if (claims.fail(event, "retry limit exceeded after stale recovery")) {
                    failed++;
                    metrics.recordResult(event, "failed");
                } else {
                    fenced++;
                    metrics.recordResult(event, "fenced");
                }
                continue;
            }
            Instant publishStartedAt = clock.instant();
            try {
                publisher.publish(event, properties.sendTimeout());
                metrics.recordPublishLatency(Duration.between(publishStartedAt, clock.instant()));
            } catch (RiskSignalPublishException exception) {
                try {
                    if (handlePublishFailure(event, exception)) {
                        if (event.attempts() >= properties.maxAttempts()) {
                            failed++;
                        } else {
                            retried++;
                        }
                    } else {
                        fenced++;
                    }
                } catch (RuntimeException stateException) {
                    fenced++;
                    metrics.recordResult(event, "state_unknown");
                    log.warn("Risk relay failure state update failed: eventId={}, sourceType={}, sourceId={}, signalType={}, attempts={}, exceptionType={}",
                            event.eventId(), event.sourceType(), event.sourceId(), event.signalType(),
                            event.attempts(), stateException.getClass().getSimpleName());
                }
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                continue;
            }

            try {
                if (claims.markSent(event)) {
                    sent++;
                    metrics.recordResult(event, "sent");
                    metrics.recordDeliveryDelay(Duration.between(event.occurredAt(), clock.instant()));
                } else {
                    fenced++;
                    metrics.recordResult(event, "fenced");
                    log.warn("Risk relay completion fenced: eventId={}, sourceType={}, sourceId={}, signalType={}, attempts={}",
                            event.eventId(), event.sourceType(), event.sourceId(), event.signalType(), event.attempts());
                }
            } catch (RuntimeException exception) {
                fenced++;
                metrics.recordResult(event, "state_unknown");
                log.warn("Risk relay state update failed after Kafka ack: eventId={}, sourceType={}, sourceId={}, signalType={}, attempts={}, exceptionType={}",
                        event.eventId(), event.sourceType(), event.sourceId(), event.signalType(),
                        event.attempts(), exception.getClass().getSimpleName());
            }
        }
        return new Result(sent, retried, failed, fenced);
    }

    private boolean handlePublishFailure(ClaimedRiskSignal event, RiskSignalPublishException exception) {
        String error = "kafka publish failed: " + rootCauseType(exception);
        boolean updated;
        if (event.attempts() >= properties.maxAttempts()) {
            updated = claims.fail(event, error);
            if (updated) {
                metrics.recordResult(event, "failed");
            }
        } else {
            updated = claims.retry(event, clock.instant().plus(backoff(event)), error);
            if (updated) {
                metrics.recordResult(event, "retried");
            }
        }
        if (!updated) {
            metrics.recordResult(event, "fenced");
        }
        log.warn("Risk relay Kafka publish failed: eventId={}, sourceType={}, sourceId={}, signalType={}, attempts={}, terminal={}, fenced={}, exceptionType={}",
                event.eventId(), event.sourceType(), event.sourceId(), event.signalType(), event.attempts(),
                event.attempts() >= properties.maxAttempts(), !updated, rootCauseType(exception));
        return updated;
    }

    private Duration backoff(ClaimedRiskSignal event) {
        long baseMillis = properties.baseBackoff().toMillis();
        long maxMillis = properties.maxBackoff().toMillis();
        long multiplier = 1L << Math.min(event.attempts() - 1, 20);
        long exponential = baseMillis > maxMillis / multiplier
                ? maxMillis
                : baseMillis * multiplier;
        long jitterRange = Math.max(1L, exponential / 5L);
        long jitter = Math.floorMod(Long.hashCode(event.id()), jitterRange);
        return Duration.ofMillis(Math.min(maxMillis, exponential + jitter));
    }

    private static String rootCauseType(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    public record Result(int sent, int retried, int failed, int fenced) {
    }
}
