package itda.notification.service;

import itda.notification.domain.NotificationTargetType;
import itda.notification.domain.NotificationType;
import itda.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes immutable in-app notification facts from already-authorized feature
 * commands. Feature services remain responsible for validating their target
 * and must call this inside the same transaction as the successful action.
 */
@Service
@RequiredArgsConstructor
public class NotificationCommandService {

    private final NotificationRepository notifications;

    /**
     * Returns true only when a previously unseen reaction notification was
     * inserted. Reactions are intentionally never recreated after undo/re-add.
     */
    @Transactional
    public boolean notifyReaction(long recipientPetId, long actorPetId, String actorNickname,
            Long actorProfileAssetId, NotificationType type, NotificationTargetType targetType,
            long targetId, Long postId, Long setlogId) {
        if (recipientPetId == actorPetId) {
            return false;
        }
        if (!type.isReaction()) {
            throw new IllegalArgumentException("Reaction notification type is required");
        }
        return notifications.insertIgnore(recipientPetId, actorPetId, type.name(), targetType.name(), targetId,
                postId, setlogId, actorNickname, actorProfileAssetId, null) == 1;
    }

    /**
     * A new comment is itself the event identity, so comments deliberately do
     * not share the reaction partial-unique rule. Call once after saving it.
     */
    @Transactional
    public boolean notifyCommentCreated(long recipientPetId, long actorPetId, String actorNickname,
            Long actorProfileAssetId, NotificationType type, long commentId, long postId,
            String commentPreview) {
        if (recipientPetId == actorPetId) {
            return false;
        }
        if (type != NotificationType.BOARD_COMMENT_CREATED && type != NotificationType.BOARD_REPLY_CREATED) {
            throw new IllegalArgumentException("Comment creation notification type is required");
        }
        return notifications.insertIgnore(recipientPetId, actorPetId, type.name(),
                NotificationTargetType.BOARD_COMMENT.name(), commentId, postId, null,
                actorNickname, actorProfileAssetId, preview(commentPreview)) == 1;
    }

    private String preview(String commentPreview) {
        if (commentPreview == null) {
            return null;
        }
        return commentPreview.length() <= 500 ? commentPreview : commentPreview.substring(0, 500);
    }
}
