package itda.risk.service;

import java.time.Instant;

public record RiskSignalAggregate(
        long signalCount,
        long totalScore,
        Instant firstDetectedAt,
        Instant lastDetectedAt
) {
}
