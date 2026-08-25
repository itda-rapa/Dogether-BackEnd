package itda.safety.dto;

import itda.risk.contract.RiskSignalType;
import itda.risk.contract.RiskSourceType;
import java.time.Instant;
import java.util.UUID;

public record SafetySignalResponse(
        long signalId,
        UUID eventId,
        RiskSourceType sourceType,
        long sourceId,
        RiskSignalType signalType,
        int score,
        int scorePolicyVersion,
        Instant occurredAt
) {
}
