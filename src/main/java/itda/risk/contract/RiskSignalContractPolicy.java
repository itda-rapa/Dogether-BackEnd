package itda.risk.contract;

import java.util.Map;
import java.util.Set;

/** RiskSignal v1이 허용하는 source/signal 조합과 비민감 metadata 형식을 정의한다. */
final class RiskSignalContractPolicy {
    private static final Set<String> USER_BLOCKED_METADATA_KEYS = Set.of("reasonCode");
    private static final Set<String> GREETING_EXPIRED_METADATA_KEYS = Set.of("ttlHours");

    private RiskSignalContractPolicy() {
    }

    static void validateCombination(RiskSourceType sourceType, RiskSignalType signalType) {
        boolean valid = switch (sourceType) {
            case USER_BLOCK -> signalType == RiskSignalType.USER_BLOCKED;
            case GREETING -> signalType == RiskSignalType.GREETING_EXPIRED;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "Unsupported source/signal combination: " + sourceType + "/" + signalType);
        }
    }

    static Map<String, String> sanitizeMetadata(
            RiskSignalType signalType,
            Map<String, String> metadata
    ) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }

        Set<String> allowedKeys = switch (signalType) {
            case USER_BLOCKED -> USER_BLOCKED_METADATA_KEYS;
            case GREETING_EXPIRED -> GREETING_EXPIRED_METADATA_KEYS;
        };
        metadata.forEach((key, value) -> {
            if (!allowedKeys.contains(key)) {
                throw new IllegalArgumentException(
                        "Unsupported metadata field for " + signalType + ": " + key);
            }
            if (value == null) {
                throw new IllegalArgumentException("metadata value must not be null");
            }
            validateMetadataValue(key, value);
        });
        return Map.copyOf(metadata);
    }

    private static void validateMetadataValue(String key, String value) {
        switch (key) {
            case "reasonCode" -> {
                if (!value.matches("[A-Z][A-Z0-9_]{0,63}")) {
                    throw new IllegalArgumentException(
                            "reasonCode must be a non-sensitive uppercase code");
                }
            }
            case "ttlHours" -> {
                try {
                    int ttlHours = Integer.parseInt(value);
                    if (ttlHours < 1 || ttlHours > 168) {
                        throw new IllegalArgumentException("ttlHours must be between 1 and 168");
                    }
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("ttlHours must be an integer", exception);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported metadata field: " + key);
        }
    }
}
