package itda.safety.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.safety.domain.SafetyEvaluationJob;
import itda.safety.domain.SafetyEvaluationJobStatus;
import itda.safety.repository.SafetyEvaluationJobJdbcRepository;
import itda.safety.repository.SafetyEvaluationJobJdbcRepository.ClaimedEvaluation;
import itda.safety.repository.SafetyReviewCaseJdbcRepository;
import itda.safety.repository.SafetyRiskSignalAggregateJdbcRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SafetyCaseEvaluationTransactionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-24T10:00:00Z");
    private final SafetyRiskSignalAggregateJdbcRepository aggregates =
            mock(SafetyRiskSignalAggregateJdbcRepository.class);
    private final SafetyReviewCaseJdbcRepository cases = mock(SafetyReviewCaseJdbcRepository.class);
    private final SafetyEvaluationJobJdbcRepository jobs = mock(SafetyEvaluationJobJdbcRepository.class);
    private final SafetyCaseEvaluationTransactionService service =
            new SafetyCaseEvaluationTransactionService(aggregates, cases, jobs, properties());

    @Test
    void scoreEqualToThresholdUpsertsCaseAndCompletesJob() {
        ClaimedEvaluation claimed = claimed(NOW.minusSeconds(1));
        when(aggregates.aggregate(41L, 42L,
                NOW.minusSeconds(1).minus(Duration.ofDays(30)), NOW.minusSeconds(1)))
                .thenReturn(new SafetyRiskSignalAggregateJdbcRepository.Aggregate(
                        3, 90, NOW.minusSeconds(30), NOW.minusSeconds(1), 11, "USER_BLOCKED"));
        when(cases.upsertOpenCase(any(Long.class), any(Long.class), any()))
                .thenReturn(java.util.Optional.of(mock(itda.safety.domain.SafetyReviewCase.class)));
        when(jobs.complete(claimed)).thenReturn(true);

        assertThat(service.evaluateAndComplete(claimed, NOW))
                .isEqualTo(SafetyCaseEvaluationTransactionService.Outcome.CASE_UPSERTED);

        var snapshot = ArgumentCaptor.forClass(itda.safety.domain.SafetyCaseSnapshot.class);
        verify(cases).upsertOpenCase(org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.eq(42L), snapshot.capture());
        assertThat(snapshot.getValue().totalScore()).isEqualTo(90);
        assertThat(snapshot.getValue().scorePolicyVersion()).isEqualTo(7);
        assertThat(snapshot.getValue().lastEvaluatedEventId()).isEqualTo(11);
        verify(jobs).complete(claimed);
    }

    @Test
    void belowThresholdOnlyCompletesJob() {
        ClaimedEvaluation claimed = claimed(NOW.minusSeconds(1));
        when(aggregates.aggregate(any(Long.class), any(Long.class), any(), any()))
                .thenReturn(new SafetyRiskSignalAggregateJdbcRepository.Aggregate(
                        2, 89, NOW.minusSeconds(30), NOW.minusSeconds(1), 11, "GREETING_EXPIRED"));
        when(jobs.complete(claimed)).thenReturn(true);

        assertThat(service.evaluateAndComplete(claimed, NOW))
                .isEqualTo(SafetyCaseEvaluationTransactionService.Outcome.BELOW_THRESHOLD);
        verify(cases, never()).upsertOpenCase(any(Long.class), any(Long.class), any());
    }

    @Test
    void fencedCompletionFailsWholeEvaluationTransaction() {
        ClaimedEvaluation claimed = claimed(NOW.minusSeconds(1));
        when(aggregates.aggregate(any(Long.class), any(Long.class), any(), any()))
                .thenReturn(new SafetyRiskSignalAggregateJdbcRepository.Aggregate(
                        1, 90, NOW.minusSeconds(1), NOW.minusSeconds(1), 11, "USER_BLOCKED"));
        when(jobs.complete(claimed)).thenReturn(false);

        assertThatThrownBy(() -> service.evaluateAndComplete(claimed, NOW))
                .isInstanceOf(SafetyCaseEvaluationTransactionService.EvaluationFencedException.class);
    }

    @Test
    void delayedJobUsesEventTimeAsWindowAnchor() {
        Instant eventOccurredAt = NOW.minus(Duration.ofDays(45));
        ClaimedEvaluation claimed = claimed(eventOccurredAt);
        when(aggregates.aggregate(41L, 42L,
                eventOccurredAt.minus(Duration.ofDays(30)), eventOccurredAt))
                .thenReturn(new SafetyRiskSignalAggregateJdbcRepository.Aggregate(
                        1, 30, eventOccurredAt, eventOccurredAt, 11, "USER_BLOCKED"));
        when(jobs.complete(claimed)).thenReturn(true);

        assertThat(service.evaluateAndComplete(claimed, NOW))
                .isEqualTo(SafetyCaseEvaluationTransactionService.Outcome.BELOW_THRESHOLD);
        verify(aggregates).aggregate(41L, 42L,
                eventOccurredAt.minus(Duration.ofDays(30)), eventOccurredAt);
    }

    private static SafetyEvaluatorProperties properties() {
        return new SafetyEvaluatorProperties(
                true, 90, Duration.ofDays(30), 7, 10, 5_000, Duration.ofMinutes(1),
                3, Duration.ofSeconds(5), Duration.ofMinutes(1));
    }

    static ClaimedEvaluation claimed(Instant occurredAt) {
        return new ClaimedEvaluation(new SafetyEvaluationJob(
                1, 11, SafetyEvaluationJobStatus.PROCESSING, 1, NOW, NOW, "worker",
                UUID.randomUUID(), null, NOW, NOW), 41, 42, occurredAt);
    }
}
