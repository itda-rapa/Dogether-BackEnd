package itda.safety.dto;

import itda.safety.domain.SafetyActionType;
import itda.safety.domain.SafetyCaseAction;
import java.time.Instant;

public record SafetyCaseActionResponse(
        long actionId,
        long adminUserId,
        SafetyActionType actionType,
        String reason,
        Instant createdAt
) {
    public static SafetyCaseActionResponse from(SafetyCaseAction value) {
        return new SafetyCaseActionResponse(
                value.id(), value.adminUserId(), value.actionType(), value.reason(), value.createdAt());
    }
}
