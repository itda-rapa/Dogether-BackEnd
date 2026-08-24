package itda.risk.contract;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private static final int MAX_METADATA_ENTRIES = 20;
    private static final int MAX_METADATA_KEY_LENGTH = 64;
    private static final int MAX_METADATA_VALUE_LENGTH = 500;
    private static final Set<String> FORBIDDEN_METADATA_KEYS = Set.of(
            "message", "messagebody", "body", "email", "jwt", "accesstoken",
            "refreshtoken", "oauthcode", "latitude", "longitude", "exactlocation"
    );

    public RiskSourceEventCommand {
        sourceType = Objects.requireNonNull(sourceType, "sourceType must not be null");
        signalType = Objects.requireNonNull(signalType, "signalType must not be null");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        requirePositive(sourceId, "sourceId");
        requirePositive(actorUserId, "actorUserId");
        requirePositive(targetUserId, "targetUserId");
        metadata = sanitizeMetadata(metadata);
    }

    private static Map<String, String> sanitizeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        if (metadata.size() > MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException("metadata must contain at most " + MAX_METADATA_ENTRIES + " entries");
        }

        metadata.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > MAX_METADATA_KEY_LENGTH) {
                throw new IllegalArgumentException("metadata key is invalid");
            }
            if (value == null || value.length() > MAX_METADATA_VALUE_LENGTH) {
                throw new IllegalArgumentException("metadata value is invalid");
            }
            String normalizedKey = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
            if (FORBIDDEN_METADATA_KEYS.contains(normalizedKey)) {
                throw new IllegalArgumentException("metadata must not contain sensitive field: " + key);
            }
        });
        return Map.copyOf(metadata);
    }

    private static void requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
