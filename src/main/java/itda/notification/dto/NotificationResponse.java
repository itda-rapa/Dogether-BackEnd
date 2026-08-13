package itda.notification.dto;

import itda.notification.domain.NotificationType;
import java.time.Instant;

public record NotificationResponse(Long notificationId, NotificationType type, Long roomId,
        String roomTitle, Long actorPetId, String actorPetNickname, Instant createdAt, Instant readAt) {
}
