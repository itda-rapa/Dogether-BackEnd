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
import itda.setlog.domain.Setlog;
import itda.setlog.repository.SetlogRepository;
import itda.user.domain.AccountStatus;
import itda.user.repository.UserRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Resolves an entire notification page with a bounded number of queries per target type. */
    public Map<Long, Boolean> resolveAll(
            List<Notification> notifications, ActivePetContext recipient, Map<Long, ChatRoom> rooms) {
        if (notifications.isEmpty()) return Map.of();
        Map<Long, Boolean> result = new HashMap<>();
        notifications.forEach(notification -> result.put(notification.getId(), false));

        Map<NotificationTargetType, Set<Long>> targetIds = targetIds(notifications);
        var viewer = users.findById(recipient.ownerUserId()).orElse(null);
        Map<Long, BoardPost> publishedPosts = posts.findAllById(targetIds.get(NotificationTargetType.BOARD_POST)).stream()
                .filter(post -> post.getStatus() == PostStatus.PUBLISHED).collect(java.util.stream.Collectors.toMap(BoardPost::getId, post -> post));
        Map<Long, BoardPostComment> activeComments = comments.findAllById(targetIds.get(NotificationTargetType.BOARD_COMMENT)).stream()
                .filter(comment -> comment.getDeletedAt() == null).collect(java.util.stream.Collectors.toMap(BoardPostComment::getId, comment -> comment));
        Set<Long> commentPostIds = activeComments.values().stream().map(BoardPostComment::getPostId).collect(java.util.stream.Collectors.toSet());
        posts.findAllById(commentPostIds).stream().filter(post -> post.getStatus() == PostStatus.PUBLISHED)
                .forEach(post -> publishedPosts.putIfAbsent(post.getId(), post));

        List<Setlog> visibleSetlogs = targetIds.get(NotificationTargetType.SETLOG).isEmpty() ? List.of()
                : setlogs.findAllByIdForShare(targetIds.get(NotificationTargetType.SETLOG), SetlogStatus.VISIBLE, PLAYABLE);
        Set<Long> authorUserIds = new HashSet<>();
        publishedPosts.values().forEach(post -> authorUserIds.add(post.getAuthorUserId()));
        activeComments.values().forEach(comment -> authorUserIds.add(comment.getAuthorUserId()));
        visibleSetlogs.forEach(setlog -> authorUserIds.add(setlog.getAuthorPet().getOwner().getId()));
        Set<Long> blockedUserIds = blocks.findBlockedUserIdsBetween(recipient.ownerUserId(), authorUserIds);

        Set<Long> visibleSetlogIds = visibleSetlogs.stream()
                .filter(setlog -> !blockedUserIds.contains(setlog.getAuthorPet().getOwner().getId()))
                .map(Setlog::getId).collect(java.util.stream.Collectors.toSet());
        Set<Long> activeRoomIds = activeRoomIds(targetIds.get(NotificationTargetType.OPEN_CHAT_ROOM), recipient.petId(), rooms);

        for (Notification notification : notifications) {
            if (notification.getTargetType() == null || notification.getTargetId() == null) continue;
            boolean available = switch (notification.getTargetType()) {
                case OPEN_CHAT_ROOM -> activeRoomIds.contains(notification.getTargetId());
                case BOARD_POST -> postVisible(publishedPosts.get(notification.getTargetId()), viewer, recipient, blockedUserIds);
                case BOARD_COMMENT -> commentVisible(activeComments.get(notification.getTargetId()), publishedPosts, viewer,
                        recipient, blockedUserIds);
                case SETLOG -> visibleSetlogIds.contains(notification.getTargetId());
            };
            result.put(notification.getId(), available);
        }
        return result;
    }

    private Map<NotificationTargetType, Set<Long>> targetIds(List<Notification> notifications) {
        Map<NotificationTargetType, Set<Long>> result = new java.util.EnumMap<>(NotificationTargetType.class);
        for (NotificationTargetType type : NotificationTargetType.values()) result.put(type, new HashSet<>());
        notifications.stream().filter(notification -> notification.getTargetType() != null && notification.getTargetId() != null)
                .forEach(notification -> result.get(notification.getTargetType()).add(notification.getTargetId()));
        return result;
    }

    private boolean postVisible(BoardPost post, itda.user.domain.User viewer, ActivePetContext recipient, Set<Long> blockedUserIds) {
        return post != null && viewer != null && viewer.isActive()
                && (post.getAuthorUserId().equals(recipient.ownerUserId())
                || post.getNeighborhoodCode().equals(viewer.getNeighborhoodCode()))
                && !blockedUserIds.contains(post.getAuthorUserId());
    }

    private boolean commentVisible(BoardPostComment comment, Map<Long, BoardPost> postsById,
            itda.user.domain.User viewer, ActivePetContext recipient, Set<Long> blockedUserIds) {
        return comment != null && !blockedUserIds.contains(comment.getAuthorUserId())
                && postVisible(postsById.get(comment.getPostId()), viewer, recipient, blockedUserIds);
    }

    private Set<Long> activeRoomIds(Collection<Long> roomIds, long petId, Map<Long, ChatRoom> rooms) {
        if (roomIds.isEmpty()) return Set.of();
        Set<Long> joined = new HashSet<>(participants.findActiveRoomIdsByPetIdAndRoomIdIn(petId, roomIds));
        joined.removeIf(roomId -> {
            ChatRoom room = rooms.get(roomId);
            return room == null || room.getOrigin() != RoomOrigin.OPEN_CHAT || room.getStatus() != RoomStatus.ACTIVE;
        });
        return joined;
    }
}
