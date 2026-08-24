package itda.comment.service;

import itda.block.service.BlockRelationshipQueryService;
import itda.boardpost.domain.BoardPost;
import itda.boardpost.repository.BoardPostRepository;
import itda.chat.domain.RoomOrigin;
import itda.chat.dto.EnsureDirectRoomResult;
import itda.chat.service.ChatRoomService;
import itda.comment.domain.BoardPostComment;
import itda.comment.repository.BoardPostCommentRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.service.InteractionPairLockService;
import itda.interaction.service.InteractionTargetQueryService;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Connects a visible root board comment to the post author's DIRECT room.
 *
 * <p>Board owns resource visibility and caller authorization. Chat owns the
 * canonical Pet pair, room reuse, participant creation, and concurrency gate.
 * Both Pet/User target states are validated from the {@link InteractionPairLockService}
 * snapshot, so the state check and the room creation observe the same locked state.
 */
@Service
public class BoardCommentDirectRoomService {

    private final BoardPostRepository posts;
    private final BoardPostCommentRepository comments;
    private final UserRepository users;
    private final ActivePetQueryService activePetQueryService;
    private final InteractionTargetQueryService interactionTargetQueryService;
    private final InteractionPairLockService interactionPairLockService;
    private final BlockRelationshipQueryService blocks;
    private final ChatRoomService chatRoomService;

    public BoardCommentDirectRoomService(
            BoardPostRepository posts,
            BoardPostCommentRepository comments,
            UserRepository users,
            ActivePetQueryService activePetQueryService,
            InteractionTargetQueryService interactionTargetQueryService,
            InteractionPairLockService interactionPairLockService,
            BlockRelationshipQueryService blocks,
            ChatRoomService chatRoomService
    ) {
        this.posts = posts;
        this.comments = comments;
        this.users = users;
        this.activePetQueryService = activePetQueryService;
        this.interactionTargetQueryService = interactionTargetQueryService;
        this.interactionPairLockService = interactionPairLockService;
        this.blocks = blocks;
        this.chatRoomService = chatRoomService;
    }

    @Transactional
    public EnsureDirectRoomResult ensureDirectRoom(
            Long userId,
            Long postId,
            Long commentId
    ) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        BoardPost post = posts.findPublishedByIdForShare(postId)
                .orElseThrow(this::postNotFound);
        BoardPostComment comment = comments.findActiveByIdForShare(commentId)
                .orElseThrow(this::commentNotFound);

        if (!Objects.equals(post.getId(), comment.getPostId())) {
            throw commentNotFound();
        }
        if (comment.getDepth() != 0
                || comment.getParentCommentId() != null
                || comment.getRootCommentId() != null) {
            throw commentNotFound();
        }

        if (!Objects.equals(actor.petId(), post.getAuthorPetId())
                && !Objects.equals(actor.petId(), comment.getAuthorPetId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        User viewer = users.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE));
        requirePostVisible(viewer, post);

        if (Objects.equals(post.getAuthorPetId(), comment.getAuthorPetId())) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_SAME_PET_FORBIDDEN);
        }

        InteractionPairContext pair = interactionPairLockService.lockInteractionPair(
                post.getAuthorPetId(),
                comment.getAuthorPetId()
        );
        interactionTargetQueryService.requireActiveTargets(
                pair,
                post.getAuthorUserId(),
                comment.getAuthorUserId()
        );

        if (Objects.equals(pair.sourceUser().userId(), pair.targetUser().userId())) {
            throw new BusinessException(ErrorCode.SAME_OWNER_INTERACTION_FORBIDDEN);
        }
        if (blocks.existsBlockBetween(
                pair.sourceUser().userId(),
                pair.targetUser().userId()
        )) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }

        return chatRoomService.ensureDirectRoom(
                post.getAuthorPetId(),
                comment.getAuthorPetId(),
                RoomOrigin.FRIEND
        );
    }

    private void requirePostVisible(User viewer, BoardPost post) {
        if (!Objects.equals(post.getAuthorUserId(), viewer.getId())
                && !Objects.equals(post.getNeighborhoodCode(), viewer.getNeighborhoodCode())) {
            throw postNotFound();
        }
    }

    private BusinessException postNotFound() {
        return new BusinessException(ErrorCode.BOARD_POST_NOT_FOUND);
    }

    private BusinessException commentNotFound() {
        return new BusinessException(ErrorCode.BOARD_POST_COMMENT_NOT_FOUND);
    }
}
