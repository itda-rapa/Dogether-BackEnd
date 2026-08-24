package itda.safety.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.safety.repository.SafetyEvaluationJobJdbcRepository;
import itda.safety.repository.SafetyEvaluationJobJdbcRepository.ClaimedEvaluation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SafetyCaseEvaluationWorkerTest {
    private static final Instant NOW = Instant.parse("2026-08-24T10:00:00Z");
    private final SafetyEvaluationJobJdbcRepository jobs = mock(SafetyEvaluationJobJdbcRepository.class);
    private final SafetyCaseEvaluationTransactionService transactions =
            mock(SafetyCaseEvaluationTransactionService.class);
    private final SafetyEvaluatorProperties properties = new SafetyEvaluatorProperties(
            true, 90, Duration.ofDays(30), 1, 2, 5_000, Duration.ofMinutes(1),
            3, Duration.ofSeconds(5), Duration.ofMinutes(1));
    private final SafetyCaseEvaluationWorker worker = new SafetyCaseEvaluationWorker(
            jobs, transactions, properties, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void reconcilesAndProcessesClaimedJob() {
        ClaimedEvaluation claimed = SafetyCaseEvaluationTransactionServiceTest.claimed(NOW.minusSeconds(1));
        when(jobs.reconcileMissing(2)).thenReturn(1);
        when(jobs.claimOne(anyString(), eq(NOW), eq(properties.lease()), eq(3)))
                .thenReturn(Optional.of(claimed), Optional.empty());
        when(transactions.evaluateAndComplete(claimed, NOW))
                .thenReturn(SafetyCaseEvaluationTransactionService.Outcome.CASE_UPSERTED);

        assertThat(worker.runOnce()).isEqualTo(
                new SafetyCaseEvaluationWorker.Result(1, 1, 1, 0, 0, 0));
    }

    @Test
    void failureStoresOnlySanitizedCodeAndRetries() {
        ClaimedEvaluation claimed = SafetyCaseEvaluationTransactionServiceTest.claimed(NOW.minusSeconds(1));
        when(jobs.claimOne(anyString(), any(), any(), anyInt()))
                .thenReturn(Optional.of(claimed), Optional.empty());
        when(transactions.evaluateAndComplete(claimed, NOW))
                .thenThrow(new IllegalStateException("secret database detail"));
        when(jobs.retry(eq(claimed), any(), eq("EVALUATION_FAILED"))).thenReturn(true);

        assertThat(worker.runOnce().retried()).isEqualTo(1);
        verify(jobs).retry(eq(claimed), any(), eq("EVALUATION_FAILED"));
        verify(jobs, never()).failTerminal(any(), eq("secret database detail"));
    }

    @Test
    void futureOrEqualOccurredAtIsRescheduledInsteadOfCompleted() {
        ClaimedEvaluation claimed = SafetyCaseEvaluationTransactionServiceTest.claimed(NOW);
        when(jobs.claimOne(anyString(), any(), any(), anyInt()))
                .thenReturn(Optional.of(claimed), Optional.empty());
        when(jobs.deferUntil(claimed, NOW.plusMillis(1))).thenReturn(true);

        assertThat(worker.runOnce().retried()).isEqualTo(1);
        verify(transactions, never()).evaluateAndComplete(any(), any());
        verify(jobs).deferUntil(claimed, NOW.plusMillis(1));
    }
}
