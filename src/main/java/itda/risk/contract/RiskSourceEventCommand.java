package itda.risk.contract;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** 원천 도메인이 Risk Outbox에 전달하는 내부 명령. 개인정보 본문은 metadata에 넣지 않는다. */
public record RiskSourceEventCommand(
        RiskSourceType sourceType,
        long sourceId,
        RiskSignalType signalType,
        long actorUserId,
        long targetUserId,
        Instant occurredAt,
        Map<String, String> metadata
) {
    public RiskSourceEventCommand {
        sourceType = Objects.requireNonNull(sourceType, "sourceType must not be null");
        signalType = Objects.requireNonNull(signalType, "signalType must not be null");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        RiskSignalContractPolicy.validateCombination(sourceType, signalType);
        requirePositive(sourceId, "sourceId");
        requirePositive(actorUserId, "actorUserId");
        requirePositive(targetUserId, "targetUserId");
        metadata = RiskSignalContractPolicy.sanitizeMetadata(signalType, metadata);
    }

    private static void requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
