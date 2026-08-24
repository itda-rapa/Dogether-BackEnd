package itda.risk.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import itda.risk.domain.RiskSignalOutboxStatus;
import itda.risk.repository.RiskSignalOutboxRepository;
import itda.risk.service.RiskSignalOutboxClaimService.ClaimedRiskSignal;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RiskSignalOutboxRelayMetrics {
    private static final List<RiskSignalOutboxStatus> BACKLOG = List.of(
            RiskSignalOutboxStatus.PENDING,
            RiskSignalOutboxStatus.PROCESSING,
            RiskSignalOutboxStatus.RETRY
    );

    private final MeterRegistry registry;

    public RiskSignalOutboxRelayMetrics(
            MeterRegistry registry,
            RiskSignalOutboxRepository repository
    ) {
        this.registry = registry;
        Gauge.builder("risk.signal.outbox.backlog", repository,
                        source -> source.countByStatusIn(BACKLOG))
                .description("Risk signal events waiting or in progress")
                .register(registry);
        Gauge.builder("risk.signal.outbox.failed", repository,
                        source -> source.countByStatus(RiskSignalOutboxStatus.FAILED))
                .description("Risk signal events requiring manual attention")
                .register(registry);
    }

    public void recordResult(ClaimedRiskSignal event, String result) {
        registry.counter("risk.signal.outbox.relay",
                        "result", result,
                        "signal", event.signalType().name().toLowerCase(java.util.Locale.ROOT))
                .increment();
    }

    public void recordPublishLatency(Duration latency) {
        Timer.builder("risk.signal.outbox.publish.latency")
                .description("Kafka publish acknowledgement latency")
                .register(registry)
                .record(latency);
    }

    public void recordDeliveryDelay(Duration delay) {
        Timer.builder("risk.signal.outbox.delivery.delay")
                .description("Delay from source occurrence to Kafka acknowledgement")
                .register(registry)
                .record(delay.isNegative() ? Duration.ZERO : delay);
    }
}
