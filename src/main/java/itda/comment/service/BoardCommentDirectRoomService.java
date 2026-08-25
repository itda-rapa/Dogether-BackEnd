package itda.comment.service;

import itda.block.service.BlockRelationshipQueryService;
import itda.boardpost.domain.BoardPost;
import itda.boardpost.domain.PostStatus;
import itda.boardpost.repository.BoardPostRepository;
import itda.chat.domain.RoomOrigin;
import itda.chat.dto.EnsureDirectRoomResult;
import itda.chat.service.ChatRoomService;
import itda.comment.domain.BoardPostComment;
import itda.comment.repository.BoardPostCommentRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.dto.LockedPetContext;
import itda.interaction.dto.LockedUserContext;
import itda.interaction.service.InteractionPairLockService;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.pet.domain.PetStatus;
import itda.user.domain.AccountStatus;
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
    private final InteractionPairLockService interactionPairLockService;
    private final BlockRelationshipQueryService blocks;
    private final ChatRoomService chatRoomService;

    public BoardCommentDirectRoomService(
            BoardPostRepository posts,
            BoardPostCommentRepository comments,
            UserRepository users,
            ActivePetQueryService activePetQueryService,
            InteractionPairLockService interactionPairLockService,
            BlockRelationshipQueryService blocks,
            ChatRoomService chatRoomService
    ) {
        this.posts = posts;
        this.comments = comments;
        this.users = users;
        this.activePetQueryService = activePetQueryService;
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
        BoardPostRepository.ShareIdentity postIdentity = posts.findShareIdentityById(postId)
                .orElseThrow(this::postNotFound);
        BoardPostCommentRepository.ShareIdentity commentIdentity = comments.findShareIdentityById(commentId)
                .orElseThrow(this::commentNotFound);

        validateInitialIdentity(postIdentity, commentIdentity);
        ActivePetContext callerActivePet = activePetQueryService.requireActivePet(userId);
        requireCallerAuthor(
                userId,
                callerActivePet,
                postIdentity,
                commentIdentity
        );

        InteractionPairContext pair = interactionPairLockService.lockInteractionPair(
                postIdentity.getAuthorPetId(),
                commentIdentity.getAuthorPetId()
        );
        validateLockedCaller(userId, callerActivePet, pair);
        requireActiveTargets(
                pair,
                postIdentity.getAuthorUserId(),
                commentIdentity.getAuthorUserId()
        );

        BoardPost post = posts.findPublishedByIdForShare(postId)
                .orElseThrow(this::postNotFound);
        BoardPostComment comment = comments.findActiveByIdForShare(commentId)
                .orElseThrow(this::commentNotFound);
        validateAuthoritativeResources(postIdentity, commentIdentity, post, comment);

        User viewer = users.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE));
        requirePostVisible(viewer, post);

        if (Objects.equals(post.getAuthorPetId(), comment.getAuthorPetId())) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_SAME_PET_FORBIDDEN);
        }

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
                RoomOrigin.BOARD_COMMENT
        );
    }

    private void requireCallerAuthor(
            Long callerUserId,
            ActivePetContext callerActivePet,
            BoardPostRepository.ShareIdentity post,
            BoardPostCommentRepository.ShareIdentity comment
    ) {
        boolean isPostAuthor = Objects.equals(callerUserId, post.getAuthorUserId())
                && Objects.equals(callerActivePet.ownerUserId(), post.getAuthorUserId())
                && Objects.equals(callerActivePet.petId(), post.getAuthorPetId());
        boolean isCommentAuthor = Objects.equals(callerUserId, comment.getAuthorUserId())
                && Objects.equals(callerActivePet.ownerUserId(), comment.getAuthorUserId())
                && Objects.equals(callerActivePet.petId(), comment.getAuthorPetId());
        if (!isPostAuthor && !isCommentAuthor) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private void validateInitialIdentity(
            BoardPostRepository.ShareIdentity post,
            BoardPostCommentRepository.ShareIdentity comment
    ) {
        if (post.getStatus() != PostStatus.PUBLISHED) {
            throw postNotFound();
        }
        if (comment.getDeletedAt() != null) {
            throw commentNotFound();
        }
        if (!Objects.equals(post.getPostId(), comment.getPostId())) {
            throw commentNotFound();
        }
        if (!Objects.equals(comment.getDepth(), (short) 0)
                || comment.getParentCommentId() != null
                || comment.getRootCommentId() != null) {
            throw commentNotFound();
        }
    }

    private void validateLockedCaller(
            Long callerUserId,
            ActivePetContext initialActivePet,
            InteractionPairContext pair
    ) {
        LockedUserContext callerUser = findLockedUser(pair, callerUserId);
        LockedPetContext callerPet = findLockedPet(pair, initialActivePet.petId());
        if (callerUser == null
                || callerPet == null
                || !Objects.equals(callerUser.userId(), initialActivePet.ownerUserId())
                || !Objects.equals(callerPet.petId(), initialActivePet.petId())
                || !Objects.equals(callerPet.ownerUserId(), initialActivePet.ownerUserId())) {
            throw new BusinessException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
        }
        if (callerUser.accountStatus() != AccountStatus.ACTIVE
                || !Objects.equals(callerUser.activePetId(), initialActivePet.petId())
                || callerPet.status() != PetStatus.ACTIVE
                || callerPet.deletedAt() != null) {
            throw new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED);
        }
    }

    private LockedUserContext findLockedUser(
            InteractionPairContext pair,
            Long callerUserId
    ) {
        if (Objects.equals(pair.sourceUser().userId(), callerUserId)) {
            return pair.sourceUser();
        }
        if (Objects.equals(pair.targetUser().userId(), callerUserId)) {
            return pair.targetUser();
        }
        return null;
    }

    private LockedPetContext findLockedPet(
            InteractionPairContext pair,
            Long activePetId
    ) {
        if (Objects.equals(pair.sourcePet().petId(), activePetId)) {
            return pair.sourcePet();
        }
        if (Objects.equals(pair.targetPet().petId(), activePetId)) {
            return pair.targetPet();
        }
        return null;
    }

    private void requireActiveTargets(
            InteractionPairContext pair,
            Long sourceOwnerUserId,
            Long targetOwnerUserId
    ) {
        if (!isActiveTarget(pair.sourcePet(), pair.sourceUser(), sourceOwnerUserId)
                || !isActiveTarget(pair.targetPet(), pair.targetUser(), targetOwnerUserId)) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }
    }

    private boolean isActiveTarget(
            LockedPetContext pet,
            LockedUserContext owner,
            Long expectedOwnerUserId
    ) {
        return expectedOwnerUserId != null
                && pet.status() == PetStatus.ACTIVE
                && pet.deletedAt() == null
                && owner.accountStatus() == AccountStatus.ACTIVE
                && Objects.equals(expectedOwnerUserId, pet.ownerUserId());
    }

    private void validateAuthoritativeResources(
            BoardPostRepository.ShareIdentity postIdentity,
            BoardPostCommentRepository.ShareIdentity commentIdentity,
            BoardPost post,
            BoardPostComment comment
    ) {
        if (!Objects.equals(post.getId(), postIdentity.getPostId())
                || !Objects.equals(post.getAuthorUserId(), postIdentity.getAuthorUserId())
                || !Objects.equals(post.getAuthorPetId(), postIdentity.getAuthorPetId())
                || post.getDeletedAt() != null
                || !Objects.equals(comment.getId(), commentIdentity.getCommentId())
                || !Objects.equals(comment.getPostId(), commentIdentity.getPostId())
                || !Objects.equals(comment.getAuthorUserId(), commentIdentity.getAuthorUserId())
                || !Objects.equals(comment.getAuthorPetId(), commentIdentity.getAuthorPetId())
                || !Objects.equals(comment.getParentCommentId(), commentIdentity.getParentCommentId())
                || !Objects.equals(comment.getRootCommentId(), commentIdentity.getRootCommentId())
                || !Objects.equals(comment.getDepth(), commentIdentity.getDepth())) {
            throw commentNotFound();
        }
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
