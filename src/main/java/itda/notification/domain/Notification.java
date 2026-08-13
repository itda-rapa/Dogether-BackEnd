package itda.notification.domain;

import itda.common.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "notifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "target_pet_id", nullable = false)
    private Long targetPetId;
    @Column(name = "actor_pet_id", nullable = false)
    private Long actorPetId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40)
    private NotificationType type;
    @Column(name = "room_id", nullable = false)
    private Long roomId;
    @Column(name = "read_at")
    private Instant readAt;

    public static Notification openChatInvite(long targetPetId, long actorPetId, long roomId) {
        Notification notification = new Notification();
        notification.targetPetId = targetPetId;
        notification.actorPetId = actorPetId;
        notification.type = NotificationType.OPEN_CHAT_INVITE;
        notification.roomId = roomId;
        return notification;
    }

    public void markRead(Instant readAt) {
        if (this.readAt == null) this.readAt = readAt;
    }
}
