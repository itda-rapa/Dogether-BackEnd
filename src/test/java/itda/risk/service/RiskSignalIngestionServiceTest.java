package itda.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.risk.contract.RiskSignalEventV1;
import itda.risk.contract.RiskSignalType;
import itda.risk.contract.RiskSourceType;
import itda.risk.policy.RiskScorePolicy;
import itda.risk.policy.RiskScorePolicy.Decision;
import itda.risk.repository.RiskSignalEventJdbcRepository;
import itda.safety.repository.SafetyEvaluationJobJdbcRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskSignalIngestionServiceTest {
    @Mock private RiskScorePolicy scorePolicy;
    @Mock private RiskSignalEventJdbcRepository repository;
    @Mock private SafetyEvaluationJobJdbcRepository evaluationJobs;
    @InjectMocks private RiskSignalIngestionService service;

    @Test
    void storesPolicyDecisionAndReturnsInserted() {
        RiskSignalEventV1 event = event();
        Decision decision = new Decision(30, 1);
        when(scorePolicy.decide(RiskSignalType.USER_BLOCKED)).thenReturn(decision);
        when(repository.insertIfAbsent(event, decision)).thenReturn(true);
        when(evaluationJobs.enqueueByEventId(event.eventId())).thenReturn(true);

        assertThat(service.ingest(event)).isEqualTo(RiskSignalIngestionService.Result.INSERTED);
        verify(repository).insertIfAbsent(event, decision);
        verify(evaluationJobs).enqueueByEventId(event.eventId());
    }

    @Test
    void duplicateEventDoesNotBecomeAnotherScore() {
        RiskSignalEventV1 event = event();
        Decision decision = new Decision(30, 1);
        when(scorePolicy.decide(RiskSignalType.USER_BLOCKED)).thenReturn(decision);
        when(repository.insertIfAbsent(event, decision)).thenReturn(false);

        assertThat(service.ingest(event)).isEqualTo(RiskSignalIngestionService.Result.DUPLICATE);
    }

    @Test
    void newlyStoredEventRequiresDurableEvaluationJob() {
        RiskSignalEventV1 event = event();
        Decision decision = new Decision(30, 1);
        when(scorePolicy.decide(RiskSignalType.USER_BLOCKED)).thenReturn(decision);
        when(repository.insertIfAbsent(event, decision)).thenReturn(true);
        when(evaluationJobs.enqueueByEventId(event.eventId())).thenReturn(false);

        assertThatThrownBy(() -> service.ingest(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Risk signal evaluation job was not enqueued");
    }

    private static RiskSignalEventV1 event() {
        return new RiskSignalEventV1(
                1, UUID.randomUUID(), RiskSourceType.USER_BLOCK, 101L,
                RiskSignalType.USER_BLOCKED, 41L, 42L, Instant.now(),
                Map.of("reasonCode", "USER_REQUEST"));
    }
}
