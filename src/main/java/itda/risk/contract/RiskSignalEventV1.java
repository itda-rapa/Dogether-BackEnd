package itda.risk.contract;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Kafka에 전달될 RiskSignal v1 표준 이벤트. */
public record RiskSignalEventV1(
        int schemaVersion,
        UUID eventId,
        RiskSourceType sourceType,
        long sourceId,
        RiskSignalType signalType,
        long actorUserId,
        long targetUserId,
        Instant occurredAt,
        Map<String, String> metadata
) {
    public static final int SCHEMA_VERSION = 1;

    public RiskSignalEventV1 {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        sourceType = Objects.requireNonNull(sourceType, "sourceType must not be null");
        signalType = Objects.requireNonNull(signalType, "signalType must not be null");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (sourceId <= 0 || actorUserId <= 0 || targetUserId <= 0) {
            throw new IllegalArgumentException("sourceId, actorUserId and targetUserId must be positive");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static RiskSignalEventV1 from(UUID eventId, RiskSourceEventCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        return new RiskSignalEventV1(
                SCHEMA_VERSION,
                eventId,
                command.sourceType(),
                command.sourceId(),
                command.signalType(),
                command.actorUserId(),
                command.targetUserId(),
                command.occurredAt(),
                command.metadata()
        );
    }

    public Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", schemaVersion);
        payload.put("eventId", eventId.toString());
        payload.put("sourceType", sourceType.name());
        payload.put("sourceId", sourceId);
        payload.put("signalType", signalType.name());
        payload.put("actorUserId", actorUserId);
        payload.put("targetUserId", targetUserId);
        payload.put("occurredAt", occurredAt.toString());
        payload.put("metadata", metadata);
        return Map.copyOf(payload);
    }
}
