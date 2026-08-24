package itda.safety.domain;

import java.time.Instant;
import java.util.Map;

public record SafetyCaseAction(
        long id,
        long caseId,
        long adminUserId,
        SafetyActionType actionType,
        String reason,
        Map<String, Object> beforeState,
        Map<String, Object> afterState,
        Instant createdAt
) {
}
