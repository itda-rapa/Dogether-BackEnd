package itda.safety.dto;

import itda.safety.domain.SafetyCaseStatus;
import itda.safety.domain.SafetyReviewCase;
import java.time.Instant;

public record SafetyCaseResponse(
        long caseId,
        SafetyUserResponse subject,
        SafetyUserResponse target,
        SafetyCaseStatus status,
        long totalScore,
        long signalCount,
        String primarySignalType,
        int scorePolicyVersion,
        Instant firstDetectedAt,
        Instant lastDetectedAt,
        Instant evaluatedAt,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public static SafetyCaseResponse from(
            SafetyReviewCase value,
            SafetyUserResponse subject,
            SafetyUserResponse target
    ) {
        return new SafetyCaseResponse(
                value.id(), subject, target, value.status(), value.totalScore(),
                value.signalCount(), value.primarySignalType(), value.scorePolicyVersion(),
                value.firstDetectedAt(), value.lastDetectedAt(), value.evaluatedAt(),
                value.version(), value.createdAt(), value.updatedAt());
    }
}
