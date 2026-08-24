package itda.safety.domain;

import java.time.Instant;
import java.util.UUID;

public record SafetyEvaluationJob(
        long id,
        long riskSignalEventId,
        SafetyEvaluationJobStatus status,
        int attempts,
        Instant availableAt,
        Instant claimedAt,
        String workerId,
        UUID claimToken,
        String lastErrorCode,
        Instant createdAt,
        Instant updatedAt
) {
}
