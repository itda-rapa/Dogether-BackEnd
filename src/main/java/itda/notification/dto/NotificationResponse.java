package itda.notification.dto;

import itda.notification.domain.NotificationType;
import itda.notification.domain.NotificationTargetType;
import java.time.Instant;

public record NotificationResponse(Long notificationId, NotificationType type,
        NotificationTargetType targetType, Long targetId, Long roomId, String roomTitle, Long postId,
        Long setlogId, Long actorPetId, String actorPetNickname, Long actorProfileAssetId,
        String commentPreview, boolean targetAvailable, Instant createdAt, Instant readAt) {
}
