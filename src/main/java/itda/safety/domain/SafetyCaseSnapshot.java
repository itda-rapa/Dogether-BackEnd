package itda.safety.domain;

import java.time.Instant;
import java.util.Objects;

public record SafetyCaseSnapshot(
        long totalScore,
        long signalCount,
        Instant firstDetectedAt,
        Instant lastDetectedAt,
        long lastEvaluatedEventId,
        String primarySignalType,
        int scorePolicyVersion,
        Instant evaluatedAt
) {
    public SafetyCaseSnapshot {
        if (totalScore < 0 || signalCount <= 0 || lastEvaluatedEventId <= 0
                || scorePolicyVersion <= 0) {
            throw new IllegalArgumentException("Safety case snapshot numbers are invalid");
        }
        Objects.requireNonNull(firstDetectedAt, "firstDetectedAt");
        Objects.requireNonNull(lastDetectedAt, "lastDetectedAt");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if (firstDetectedAt.isAfter(lastDetectedAt) || evaluatedAt.isBefore(lastDetectedAt)) {
            throw new IllegalArgumentException("Safety case snapshot timestamps are invalid");
        }
        if (primarySignalType == null || primarySignalType.isBlank() || primarySignalType.length() > 50) {
            throw new IllegalArgumentException("primarySignalType is invalid");
        }
    }
}
