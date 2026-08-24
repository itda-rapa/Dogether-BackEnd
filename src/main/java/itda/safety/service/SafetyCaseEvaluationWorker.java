package itda.safety.service;

import itda.safety.repository.SafetyEvaluationJobJdbcRepository;
import itda.safety.repository.SafetyEvaluationJobJdbcRepository.ClaimedEvaluation;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SafetyCaseEvaluationWorker {
    static final String ERROR_EVALUATION_FAILED = "EVALUATION_FAILED";
    static final String ERROR_RETRY_EXHAUSTED = "EVALUATION_RETRY_EXHAUSTED";

    private final SafetyEvaluationJobJdbcRepository jobs;
    private final SafetyCaseEvaluationTransactionService transactions;
    private final SafetyEvaluatorProperties properties;
    private final Clock clock;
    private final String workerId;

    public SafetyCaseEvaluationWorker(
            SafetyEvaluationJobJdbcRepository jobs,
            SafetyCaseEvaluationTransactionService transactions,
            SafetyEvaluatorProperties properties,
            Clock clock
    ) {
        this.jobs = jobs;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
        this.workerId = ManagementFactory.getRuntimeMXBean().getName();
    }

    public Result runOnce() {
        int reconciled = jobs.reconcileMissing(properties.batchSize());
        int completed = 0;
        int cases = 0;
        int retried = 0;
        int failed = 0;
        int fenced = 0;
        for (int processed = 0; processed < properties.batchSize(); processed++) {
            Instant now = clock.instant();
            var claimed = jobs.claimOne(workerId, now, properties.lease(), properties.maxAttempts());
            if (claimed.isEmpty()) {
                break;
            }
            ClaimedEvaluation job = claimed.get();
            if (!job.eventOccurredAt().isBefore(now)) {
                if (jobs.deferUntil(job, job.eventOccurredAt().plusMillis(1))) {
                    retried++;
                } else {
                    fenced++;
                }
                continue;
            }
            try {
                var outcome = transactions.evaluateAndComplete(job, now);
                completed++;
                if (outcome == SafetyCaseEvaluationTransactionService.Outcome.CASE_UPSERTED) {
                    cases++;
                }
            } catch (RuntimeException exception) {
                if (job.job().attempts() >= properties.maxAttempts()) {
                    if (jobs.failTerminal(job, ERROR_RETRY_EXHAUSTED)) {
                        failed++;
                    } else {
                        fenced++;
                    }
                } else if (jobs.retry(job, now.plus(backoff(job)), ERROR_EVALUATION_FAILED)) {
                    retried++;
                } else {
                    fenced++;
                }
                log.warn("Safety case evaluation failed: jobId={}, attempts={}, terminal={}, exceptionType={}",
                        job.job().id(), job.job().attempts(),
                        job.job().attempts() >= properties.maxAttempts(),
                        exception.getClass().getSimpleName());
            }
        }
        return new Result(reconciled, completed, cases, retried, failed, fenced);
    }

    private Duration backoff(ClaimedEvaluation claimed) {
        long baseMillis = properties.baseBackoff().toMillis();
        long maxMillis = properties.maxBackoff().toMillis();
        long multiplier = 1L << Math.min(claimed.job().attempts() - 1, 20);
        long exponential = baseMillis > maxMillis / multiplier
                ? maxMillis : baseMillis * multiplier;
        long jitterRange = Math.max(1L, exponential / 5L);
        long jitter = Math.floorMod(Long.hashCode(claimed.job().id()), jitterRange);
        return Duration.ofMillis(Math.min(maxMillis, exponential + jitter));
    }

    public record Result(
            int reconciled, int completed, int cases, int retried, int failed, int fenced
    ) { }
}
