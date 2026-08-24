package itda.safety.domain;

import java.time.Instant;

public record SafetyReviewCase(
        long id,
        long subjectUserId,
        Long targetUserId,
        SafetyCaseStatus status,
        long totalScore,
        long signalCount,
        String primarySignalType,
        int scorePolicyVersion,
        Instant firstDetectedAt,
        Instant lastDetectedAt,
        long lastEvaluatedEventId,
        Instant evaluatedAt,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
