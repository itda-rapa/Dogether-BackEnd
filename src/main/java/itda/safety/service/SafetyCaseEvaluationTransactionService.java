package itda.safety.service;

import itda.safety.domain.SafetyCaseSnapshot;
import itda.safety.repository.SafetyEvaluationJobJdbcRepository;
import itda.safety.repository.SafetyEvaluationJobJdbcRepository.ClaimedEvaluation;
import itda.safety.repository.SafetyReviewCaseJdbcRepository;
import itda.safety.repository.SafetyRiskSignalAggregateJdbcRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SafetyCaseEvaluationTransactionService {
    private final SafetyRiskSignalAggregateJdbcRepository aggregates;
    private final SafetyReviewCaseJdbcRepository cases;
    private final SafetyEvaluationJobJdbcRepository jobs;
    private final SafetyEvaluatorProperties properties;

    @Transactional
    public Outcome evaluateAndComplete(ClaimedEvaluation claimed, Instant evaluatedAt) {
        Instant anchor = aggregates.findLatestOccurredAt(
                        claimed.subjectUserId(), claimed.targetUserId(), evaluatedAt)
                .filter(latest -> latest.isAfter(claimed.eventOccurredAt()))
                .orElse(claimed.eventOccurredAt());
        var aggregate = aggregates.aggregate(
                claimed.subjectUserId(), claimed.targetUserId(),
                anchor.minus(properties.window()), anchor);
        boolean caseOpenedOrUpdated = false;
        if (aggregate.signalCount() > 0 && aggregate.totalScore() >= properties.threshold()) {
            SafetyCaseSnapshot snapshot = new SafetyCaseSnapshot(
                    aggregate.totalScore(), aggregate.signalCount(), aggregate.firstDetectedAt(),
                    aggregate.lastDetectedAt(), aggregate.lastEvaluatedEventId(),
                    aggregate.primarySignalType(),
                    properties.policyVersion(), evaluatedAt);
            caseOpenedOrUpdated = cases.upsertOpenCase(
                    claimed.subjectUserId(), claimed.targetUserId(), snapshot).isPresent();
        }
        if (!jobs.complete(claimed)) {
            throw new EvaluationFencedException();
        }
        return caseOpenedOrUpdated ? Outcome.CASE_UPSERTED : Outcome.BELOW_THRESHOLD;
    }

    public enum Outcome { CASE_UPSERTED, BELOW_THRESHOLD }

    static final class EvaluationFencedException extends RuntimeException {
        EvaluationFencedException() {
            super("Safety evaluation claim was fenced");
        }
    }
}
