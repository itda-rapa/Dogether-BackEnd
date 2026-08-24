package itda.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import itda.risk.contract.RiskSignalEventV1;
import itda.risk.contract.RiskSignalType;
import itda.risk.contract.RiskSourceEventCommand;
import itda.risk.contract.RiskSourceType;
import itda.risk.repository.RiskSignalOutboxJdbcRepository;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TransactionalRiskSourceEventPublisherTest {

    @Test
    void enqueueBuildsV1EventAndDelegatesToOutbox() {
        RiskSignalOutboxJdbcRepository outbox = mock(RiskSignalOutboxJdbcRepository.class);
        TransactionalRiskSourceEventPublisher publisher = new TransactionalRiskSourceEventPublisher(outbox);
        RiskSourceEventCommand command = new RiskSourceEventCommand(
                RiskSourceType.GREETING, 30L, RiskSignalType.GREETING_EXPIRED,
                41L, 42L, Instant.parse("2026-08-24T09:30:00Z"), Map.of("ttlHours", "24"));

        publisher.enqueue(command);

        ArgumentCaptor<RiskSignalEventV1> eventCaptor = ArgumentCaptor.forClass(RiskSignalEventV1.class);
        verify(outbox).insertIfAbsent(eventCaptor.capture());
        RiskSignalEventV1 event = eventCaptor.getValue();
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.eventId()).isNotNull();
        assertThat(event.sourceType()).isEqualTo(RiskSourceType.GREETING);
        assertThat(event.signalType()).isEqualTo(RiskSignalType.GREETING_EXPIRED);
        assertThat(event.metadata()).containsEntry("ttlHours", "24");
    }
}
