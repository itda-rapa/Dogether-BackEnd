package itda.safety.dto;

import itda.risk.contract.RiskSignalType;
import itda.risk.contract.RiskSourceType;
import java.time.Instant;

public record SafetyEvidenceResponse(
        long signalId,
        RiskSignalType signalType,
        RiskSourceType sourceType,
        long sourceId,
        Instant occurredAt,
        AccessStatus accessStatus,
        SourceSummary source
) {
    public enum AccessStatus {
        AVAILABLE,
        SOURCE_NOT_FOUND,
        UNSUPPORTED
    }

    public record SourceSummary(
            String subjectPublicTag,
            String targetPublicTag,
            String sourceStatus,
            Instant sourceOccurredAt
    ) {
    }
}
