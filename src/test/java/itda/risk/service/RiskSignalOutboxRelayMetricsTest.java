package itda.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import itda.risk.domain.RiskSignalOutboxStatus;
import itda.risk.repository.RiskSignalOutboxRepository;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RiskSignalOutboxRelayMetricsTest {
    @Test
    void gaugesReadCurrentStateAndResultTimersRemainLowCardinality() {
        RiskSignalOutboxRepository repository = mock(RiskSignalOutboxRepository.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(repository.countByStatusIn(anyCollection())).thenReturn(7L, 4L);
        when(repository.countByStatus(RiskSignalOutboxStatus.FAILED)).thenReturn(2L, 3L);
        RiskSignalOutboxRelayMetrics metrics = new RiskSignalOutboxRelayMetrics(registry, repository);

        assertThat(registry.get("risk.signal.outbox.backlog").gauge().value()).isEqualTo(7.0);
        assertThat(registry.get("risk.signal.outbox.failed").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("risk.signal.outbox.backlog").gauge().value()).isEqualTo(4.0);
        assertThat(registry.get("risk.signal.outbox.failed").gauge().value()).isEqualTo(3.0);

        metrics.recordResult(RiskSignalKafkaPublisherTest.event(1), "sent");
        metrics.recordPublishLatency(Duration.ofMillis(20));
        metrics.recordDeliveryDelay(Duration.ofSeconds(2));

        assertThat(registry.get("risk.signal.outbox.relay")
                .tags("result", "sent", "signal", "user_blocked").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("risk.signal.outbox.publish.latency").timer().count()).isEqualTo(1);
        assertThat(registry.get("risk.signal.outbox.delivery.delay").timer().count()).isEqualTo(1);
    }
}
