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
    @Enumerated(EnumType.STRING) @Column(name = "target_type", nullable = false, length = 40)
    private NotificationTargetType targetType;
    @Column(name = "target_id", nullable = false)
    private Long targetId;
    @Column(name = "room_id")
    private Long roomId;
    @Column(name = "post_id")
    private Long postId;
    @Column(name = "setlog_id")
    private Long setlogId;
    @Column(name = "actor_pet_nickname_snapshot", length = 30)
    private String actorPetNicknameSnapshot;
    @Column(name = "actor_profile_asset_id_snapshot")
    private Long actorProfileAssetIdSnapshot;
    @Column(name = "comment_preview_snapshot", length = 500)
    private String commentPreviewSnapshot;
    @Column(name = "read_at")
    private Instant readAt;

    public static Notification openChatInvite(long targetPetId, long actorPetId, long roomId) {
        return openChatInvite(targetPetId, actorPetId, roomId, null, null);
    }

    public static Notification openChatInvite(long targetPetId, long actorPetId, long roomId,
            String actorPetNicknameSnapshot, Long actorProfileAssetIdSnapshot) {
        Notification notification = new Notification();
        notification.targetPetId = targetPetId;
        notification.actorPetId = actorPetId;
        notification.type = NotificationType.OPEN_CHAT_INVITE;
        notification.targetType = NotificationTargetType.OPEN_CHAT_ROOM;
        notification.targetId = roomId;
        notification.roomId = roomId;
        notification.actorPetNicknameSnapshot = actorPetNicknameSnapshot;
        notification.actorProfileAssetIdSnapshot = actorProfileAssetIdSnapshot;
        return notification;
    }

    public static Notification interaction(long targetPetId, long actorPetId, NotificationType type,
            NotificationTargetType targetType, long targetId, Long postId, Long setlogId,
            String actorPetNicknameSnapshot, Long actorProfileAssetIdSnapshot, String commentPreviewSnapshot) {
        if (type == NotificationType.OPEN_CHAT_INVITE) {
            throw new IllegalArgumentException("Open chat invitations must use openChatInvite");
        }
        Notification notification = new Notification();
        notification.targetPetId = targetPetId;
        notification.actorPetId = actorPetId;
        notification.type = type;
        notification.targetType = targetType;
        notification.targetId = targetId;
        notification.postId = postId;
        notification.setlogId = setlogId;
        notification.actorPetNicknameSnapshot = actorPetNicknameSnapshot;
        notification.actorProfileAssetIdSnapshot = actorProfileAssetIdSnapshot;
        notification.commentPreviewSnapshot = commentPreviewSnapshot;
        return notification;
    }

    public void markRead(Instant readAt) {
        if (this.readAt == null) this.readAt = readAt;
    }
}
