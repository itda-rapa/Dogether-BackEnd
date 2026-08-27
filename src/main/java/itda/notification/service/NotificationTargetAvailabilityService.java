package itda.notification.service;

import itda.block.service.BlockRelationshipQueryService;
import itda.boardpost.domain.BoardPost;
import itda.boardpost.domain.PostStatus;
import itda.boardpost.repository.BoardPostRepository;
import itda.chat.domain.ChatRoom;
import itda.chat.domain.RoomOrigin;
import itda.chat.domain.RoomStatus;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.comment.domain.BoardPostComment;
import itda.comment.repository.BoardPostCommentRepository;
import itda.media.domain.MediaStatus;
import itda.notification.domain.Notification;
import itda.notification.domain.NotificationTargetType;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.ActivePetContext;
import itda.setlog.domain.SetlogStatus;
import itda.setlog.repository.SetlogRepository;
import itda.user.domain.AccountStatus;
import itda.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Rechecks mutable target visibility without revealing why it is unavailable. */
@Service
@RequiredArgsConstructor
public class NotificationTargetAvailabilityService {
    private static final List<MediaStatus> PLAYABLE = List.of(MediaStatus.UPLOADED, MediaStatus.COMPLETED);

    private final BoardPostRepository posts;
    private final BoardPostCommentRepository comments;
    private final SetlogRepository setlogs;
    private final ChatRoomParticipantRepository participants;
    private final BlockRelationshipQueryService blocks;
    private final UserRepository users;

    public boolean isAvailable(Notification notification, ActivePetContext recipient, ChatRoom room) {
        if (notification.getTargetType() == null || notification.getTargetId() == null) return false;
        return switch (notification.getTargetType()) {
            case OPEN_CHAT_ROOM -> room != null && room.getOrigin() == RoomOrigin.OPEN_CHAT
                    && room.getStatus() == RoomStatus.ACTIVE
                    && participants.existsByRoomIdAndPetIdAndLeftAtIsNull(room.getId(), recipient.petId());
            case BOARD_POST -> postAvailable(notification.getTargetId(), recipient);
            case BOARD_COMMENT -> comments.findByIdAndDeletedAtIsNull(notification.getTargetId())
                    .map(BoardPostComment::getPostId).map(postId -> postAvailable(postId, recipient)).orElse(false);
            case SETLOG -> setlogs.findVisibleDetailById(notification.getTargetId(), recipient.ownerUserId(),
                    SetlogStatus.VISIBLE, PLAYABLE, PetStatus.ACTIVE, AccountStatus.ACTIVE).isPresent();
        };
    }

    private boolean postAvailable(long postId, ActivePetContext recipient) {
        return posts.findByIdAndStatus(postId, PostStatus.PUBLISHED).map(post -> visibleTo(post, recipient)).orElse(false);
    }

    private boolean visibleTo(BoardPost post, ActivePetContext recipient) {
        return users.findById(recipient.ownerUserId()).filter(user -> user.isActive()
                && (post.getAuthorUserId().equals(recipient.ownerUserId())
                || post.getNeighborhoodCode().equals(user.getNeighborhoodCode())))
                .filter(ignored -> !blocks.existsBlockBetween(recipient.ownerUserId(), post.getAuthorUserId()))
                .isPresent();
    }
}
