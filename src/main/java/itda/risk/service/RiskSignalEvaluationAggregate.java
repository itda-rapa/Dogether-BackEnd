package itda.risk.service;

import java.time.Instant;

/**
 * Risk 도메인이 공개하는 Safety 평가용 기간 집계 projection.
 */
public record RiskSignalEvaluationAggregate(
        long signalCount,
        long totalScore,
        Instant firstDetectedAt,
        Instant lastDetectedAt,
        long lastEvaluatedEventId,
        String primarySignalType
) {
}
