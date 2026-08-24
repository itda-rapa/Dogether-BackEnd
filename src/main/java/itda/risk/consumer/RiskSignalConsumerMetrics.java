package itda.risk.consumer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import itda.risk.contract.RiskSignalEventV1;
import itda.risk.service.RiskSignalIngestionService.Result;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RiskSignalConsumerMetrics {
    private final MeterRegistry registry;

    public void recordConsumed(RiskSignalEventV1 event, Result result, Duration processingTime) {
        safely(() -> {
            registry.counter("risk.signal.consumer.events",
                    "signal", event.signalType().name(), "result", result.name()).increment();
            Timer.builder("risk.signal.consumer.processing")
                    .tag("result", result.name())
                    .register(registry)
                    .record(processingTime);
        });
    }

    public void recordContractFailure(RiskSignalContractException.Reason reason) {
        safely(() -> registry.counter(
                "risk.signal.consumer.contract.failures", "reason", reason.name()).increment());
    }

    public void recordRetry(Throwable throwable, int deliveryAttempt) {
        safely(() -> registry.counter("risk.signal.consumer.retries",
                "category", category(throwable), "attempt", attemptBucket(deliveryAttempt))
                .increment());
    }

    public void recordDlt(String reason, boolean success) {
        safely(() -> registry.counter("risk.signal.consumer.dlt",
                "reason", reason, "result", success ? "SUCCESS" : "FAILURE").increment());
    }

    private static String category(Throwable throwable) {
        return hasCause(throwable, RiskSignalContractException.class) ? "CONTRACT" : "PROCESSING";
    }

    private static String attemptBucket(int deliveryAttempt) {
        return deliveryAttempt >= 5 ? "5_PLUS" : Integer.toString(deliveryAttempt);
    }

    static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void safely(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException exception) {
            log.warn("Risk signal metric recording failed: type={}",
                    exception.getClass().getSimpleName());
        }
    }
}
