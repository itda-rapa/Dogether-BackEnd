package itda.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import itda.block.service.BlockRelationshipQueryService;
import itda.boardpost.domain.BoardPost;
import itda.boardpost.domain.PostStatus;
import itda.boardpost.repository.BoardPostRepository;
import itda.chat.domain.RoomOrigin;
import itda.chat.dto.EnsureDirectRoomResult;
import itda.chat.service.ChatRoomService;
import itda.comment.domain.BoardPostComment;
import itda.comment.repository.BoardPostCommentRepository;
import itda.comment.service.BoardCommentDirectRoomService;
import itda.common.exception.BusinessException;
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.dto.LockedPetContext;
import itda.interaction.dto.LockedUserContext;
import itda.interaction.service.InteractionPairLockService;
import itda.interaction.service.InteractionTargetQueryService;
import itda.pet.domain.PetStatus;
import itda.user.domain.AccountStatus;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BoardCommentDirectRoomServiceTest {

    @Mock private BoardPostRepository posts;
    @Mock private BoardPostCommentRepository comments;
    @Mock private UserRepository users;
    @Mock private InteractionPairLockService pairLocks;
    @Mock private BlockRelationshipQueryService blocks;
    @Mock private ChatRoomService chatRooms;

    private final InteractionTargetQueryService targets = new InteractionTargetQueryService();

    @Test
    void readsIdentityLocksPairThenReReadsAuthoritativeResourcesBeforeVisibility() {
        BoardPost post = post(10L, 1L, 11L);
        BoardPostComment comment = rootComment(20L, 10L, 2L, 22L);
        stubBoardAndLock(post, comment, pair(11L, 1L, 22L, 2L));
        given(users.findById(1L)).willReturn(Optional.of(user(1L)));
        given(blocks.existsBlockBetween(1L, 2L)).willReturn(false);
        given(chatRooms.ensureDirectRoom(11L, 22L, RoomOrigin.BOARD_COMMENT))
                .willReturn(new EnsureDirectRoomResult(99L, false));

        EnsureDirectRoomResult result = service().ensureDirectRoom(1L, 10L, 20L);

        assertThat(result).isEqualTo(new EnsureDirectRoomResult(99L, false));
        InOrder order = inOrder(posts, comments, pairLocks);
        order.verify(posts).findShareIdentityById(10L);
        order.verify(comments).findShareIdentityById(20L);
        order.verify(pairLocks).lockInteractionPair(11L, 22L);
        order.verify(posts).findPublishedByIdForShare(10L);
        order.verify(comments).findActiveByIdForShare(20L);
        then(chatRooms).should().ensureDirectRoom(11L, 22L, RoomOrigin.BOARD_COMMENT);
    }

    @Test
    void allowsTheCommentAuthorWhenItsLockedActivePetMatchesTheComment() {
        BoardPost post = post(10L, 1L, 11L);
        BoardPostComment comment = rootComment(20L, 10L, 2L, 22L);
        stubBoardAndLock(post, comment, pair(11L, 1L, 22L, 2L));
        given(users.findById(2L)).willReturn(Optional.of(user(2L)));
        given(blocks.existsBlockBetween(1L, 2L)).willReturn(false);
        given(chatRooms.ensureDirectRoom(11L, 22L, RoomOrigin.BOARD_COMMENT))
                .willReturn(new EnsureDirectRoomResult(99L, true));

        assertThat(service().ensureDirectRoom(2L, 10L, 20L).isNew()).isTrue();
    }

    @Test
    void rejectsAuthorUserWhenLockedActivePetChanged() {
        BoardPost post = post(10L, 1L, 11L);
        BoardPostComment comment = rootComment(20L, 10L, 2L, 22L);
        stubBoardAndLock(post, comment, new InteractionPairContext(
                new LockedUserContext(1L, AccountStatus.ACTIVE, 99L, "post-owner"),
                new LockedUserContext(2L, AccountStatus.ACTIVE, 22L, "comment-owner"),
                new LockedPetContext(11L, 1L, PetStatus.ACTIVE, null),
                new LockedPetContext(22L, 2L, PetStatus.ACTIVE, null)
        ));

        assertCode(() -> service().ensureDirectRoom(1L, 10L, 20L), "FORBIDDEN");
        then(users).shouldHaveNoInteractions();
        then(chatRooms).shouldHaveNoInteractions();
    }

    @Test
    void rejectsRepliesBeforePairLock() {
        BoardPost post = post(10L, 1L, 11L);
        BoardPostComment reply = BoardPostComment.reply(
                10L, 2L, 22L, "reply", 19L, 19L, (short) 1
        );
        ReflectionTestUtils.setField(reply, "id", 20L);
        BoardPostRepository.ShareIdentity postIdentity = postIdentity(post);
        BoardPostCommentRepository.ShareIdentity replyIdentity = commentIdentity(reply);
        given(posts.findShareIdentityById(10L)).willReturn(Optional.of(postIdentity));
        given(comments.findShareIdentityById(20L)).willReturn(Optional.of(replyIdentity));

        assertCode(() -> service().ensureDirectRoom(1L, 10L, 20L), "BOARD_POST_COMMENT_NOT_FOUND");
        then(pairLocks).shouldHaveNoInteractions();
        then(chatRooms).shouldHaveNoInteractions();
    }

    @Test
    void rejectsNonPublishedPostBeforePairLock() {
        BoardPost deletedPost = post(10L, 1L, 11L);
        deletedPost.delete(Instant.parse("2024-01-01T00:00:00Z"));
        BoardPostComment comment = rootComment(20L, 10L, 2L, 22L);
        BoardPostRepository.ShareIdentity postIdentity = postIdentity(deletedPost);
        BoardPostCommentRepository.ShareIdentity commentIdentity = commentIdentity(comment);
        given(posts.findShareIdentityById(10L)).willReturn(Optional.of(postIdentity));
        given(comments.findShareIdentityById(20L)).willReturn(Optional.of(commentIdentity));

        assertCode(() -> service().ensureDirectRoom(3L, 10L, 20L), "BOARD_POST_NOT_FOUND");
        then(pairLocks).shouldHaveNoInteractions();
        then(chatRooms).shouldHaveNoInteractions();
    }

    @Test
    void rejectsDeletedCommentBeforePairLock() {
        BoardPost post = post(10L, 1L, 11L);
        BoardPostComment deletedComment = rootComment(20L, 10L, 2L, 22L);
        deletedComment.delete(Instant.parse("2024-01-01T00:00:00Z"));
        BoardPostRepository.ShareIdentity postIdentity = postIdentity(post);
        BoardPostCommentRepository.ShareIdentity commentIdentity = commentIdentity(deletedComment);
        given(posts.findShareIdentityById(10L)).willReturn(Optional.of(postIdentity));
        given(comments.findShareIdentityById(20L)).willReturn(Optional.of(commentIdentity));

        assertCode(() -> service().ensureDirectRoom(3L, 10L, 20L), "BOARD_POST_COMMENT_NOT_FOUND");
        then(pairLocks).shouldHaveNoInteractions();
        then(chatRooms).shouldHaveNoInteractions();
    }

    @Test
    void rejectsAThirdPartyBeforeReadingCallerVisibility() {
        BoardPost post = post(10L, 1L, 11L);
        BoardPostComment comment = rootComment(20L, 10L, 2L, 22L);
        stubBoardAndLock(post, comment, pair(11L, 1L, 22L, 2L));

        assertCode(() -> service().ensureDirectRoom(3L, 10L, 20L), "FORBIDDEN");
        then(users).shouldHaveNoInteractions();
        then(chatRooms).shouldHaveNoInteractions();
    }

    @Test
    void preservesSamePetSameOwnerAndBlockPolicies() {
        BoardPost samePetPost = post(10L, 1L, 11L);
        BoardPostComment samePet = rootComment(20L, 10L, 1L, 11L);
        stubBoardAndLock(samePetPost, samePet, pair(11L, 1L, 11L, 1L));
        given(users.findById(1L)).willReturn(Optional.of(user(1L)));
        assertCode(() -> service().ensureDirectRoom(1L, 10L, 20L), "CHAT_ROOM_SAME_PET_FORBIDDEN");

        BoardPostComment sameOwner = rootComment(21L, 10L, 1L, 22L);
        stubBoardAndLock(samePetPost, sameOwner, pair(11L, 1L, 22L, 1L));
        given(users.findById(1L)).willReturn(Optional.of(user(1L)));
        assertCode(() -> service().ensureDirectRoom(1L, 10L, 21L), "SAME_OWNER_INTERACTION_FORBIDDEN");

        BoardPostComment blocked = rootComment(22L, 10L, 2L, 22L);
        stubBoardAndLock(samePetPost, blocked, pair(11L, 1L, 22L, 2L));
        given(users.findById(1L)).willReturn(Optional.of(user(1L)));
        given(blocks.existsBlockBetween(1L, 2L)).willReturn(true);
        assertCode(() -> service().ensureDirectRoom(1L, 10L, 22L), "CHAT_ROOM_NOT_FOUND");
        then(chatRooms).shouldHaveNoInteractions();
    }

    @Test
    void hidesInactiveOrMismatchedLockedTargets() {
        BoardPost post = post(10L, 1L, 11L);
        BoardPostComment comment = rootComment(20L, 10L, 2L, 22L);
        InteractionPairContext[] pairs = {
                pair(11L, 1L, PetStatus.ACTIVE, null, AccountStatus.ACTIVE,
                        22L, 2L, PetStatus.SUSPENDED, null, AccountStatus.ACTIVE),
                pair(11L, 1L, PetStatus.ACTIVE, null, AccountStatus.ACTIVE,
                        22L, 2L, PetStatus.DELETED, Instant.parse("2024-01-01T00:00:00Z"), AccountStatus.ACTIVE),
                pair(11L, 1L, PetStatus.ACTIVE, null, AccountStatus.ACTIVE,
                        22L, 2L, PetStatus.ACTIVE, null, AccountStatus.SUSPENDED),
                pair(11L, 99L, PetStatus.ACTIVE, null, AccountStatus.ACTIVE,
                        22L, 2L, PetStatus.ACTIVE, null, AccountStatus.ACTIVE)
        };

        for (InteractionPairContext lockedPair : pairs) {
            stubBoardAndLock(post, comment, lockedPair);
            assertCode(() -> service().ensureDirectRoom(1L, 10L, 20L), "CHAT_ROOM_NOT_FOUND");
        }
        then(chatRooms).shouldHaveNoInteractions();
    }

    @Test
    void rejectsAuthoritativeDeletionAuthorChangeAndRelationshipChange() {
        BoardPost post = post(10L, 1L, 11L);
        BoardPostComment comment = rootComment(20L, 10L, 2L, 22L);
        stubBoardAndLock(post, comment, pair(11L, 1L, 22L, 2L));
        given(posts.findPublishedByIdForShare(10L)).willReturn(Optional.empty());
        assertCode(() -> service().ensureDirectRoom(1L, 10L, 20L), "BOARD_POST_NOT_FOUND");

        stubBoardAndLock(post, comment, pair(11L, 1L, 22L, 2L));
        BoardPost changedAuthor = post(10L, 99L, 11L);
        given(posts.findPublishedByIdForShare(10L)).willReturn(Optional.of(changedAuthor));
        assertCode(() -> service().ensureDirectRoom(1L, 10L, 20L), "BOARD_POST_COMMENT_NOT_FOUND");

        stubBoardAndLock(post, comment, pair(11L, 1L, 22L, 2L));
        BoardPostComment changedRelationship = rootComment(20L, 99L, 2L, 22L);
        given(comments.findActiveByIdForShare(20L)).willReturn(Optional.of(changedRelationship));
        assertCode(() -> service().ensureDirectRoom(1L, 10L, 20L), "BOARD_POST_COMMENT_NOT_FOUND");
    }

    @Test
    void rejectsMissingAuthoritativeCommentWithExistingBoardError() {
        BoardPost post = post(10L, 1L, 11L);
        BoardPostComment comment = rootComment(20L, 10L, 2L, 22L);
        stubBoardAndLock(post, comment, pair(11L, 1L, 22L, 2L));
        given(comments.findActiveByIdForShare(20L)).willReturn(Optional.empty());

        assertCode(() -> service().ensureDirectRoom(1L, 10L, 20L), "BOARD_POST_COMMENT_NOT_FOUND");
    }

    private void stubBoardAndLock(
            BoardPost post,
            BoardPostComment comment,
            InteractionPairContext lockedPair
    ) {
        BoardPostRepository.ShareIdentity postIdentity = postIdentity(post);
        BoardPostCommentRepository.ShareIdentity commentIdentity = commentIdentity(comment);
        lenient().when(posts.findShareIdentityById(post.getId())).thenReturn(Optional.of(postIdentity));
        lenient().when(comments.findShareIdentityById(comment.getId())).thenReturn(Optional.of(commentIdentity));
        lenient().when(pairLocks.lockInteractionPair(post.getAuthorPetId(), comment.getAuthorPetId()))
                .thenReturn(lockedPair);
        lenient().when(posts.findPublishedByIdForShare(post.getId())).thenReturn(Optional.of(post));
        lenient().when(comments.findActiveByIdForShare(comment.getId())).thenReturn(Optional.of(comment));
    }

    private BoardCommentDirectRoomService service() {
        return new BoardCommentDirectRoomService(
                posts, comments, users, targets, pairLocks, blocks, chatRooms
        );
    }

    private InteractionPairContext pair(
            long postPetId,
            long postOwnerId,
            long commentPetId,
            long commentOwnerId
    ) {
        return pair(
                postPetId, postOwnerId, PetStatus.ACTIVE, null, AccountStatus.ACTIVE,
                commentPetId, commentOwnerId, PetStatus.ACTIVE, null, AccountStatus.ACTIVE
        );
    }

    private InteractionPairContext pair(
            long postPetId,
            long postOwnerId,
            PetStatus postStatus,
            Instant postDeletedAt,
            AccountStatus postOwnerStatus,
            long commentPetId,
            long commentOwnerId,
            PetStatus commentStatus,
            Instant commentDeletedAt,
            AccountStatus commentOwnerStatus
    ) {
        return new InteractionPairContext(
                new LockedUserContext(postOwnerId, postOwnerStatus, postPetId, "post-owner"),
                new LockedUserContext(commentOwnerId, commentOwnerStatus, commentPetId, "comment-owner"),
                new LockedPetContext(postPetId, postOwnerId, postStatus, postDeletedAt),
                new LockedPetContext(commentPetId, commentOwnerId, commentStatus, commentDeletedAt)
        );
    }

    private BoardPostRepository.ShareIdentity postIdentity(BoardPost post) {
        BoardPostRepository.ShareIdentity identity = mock(BoardPostRepository.ShareIdentity.class);
        lenient().when(identity.getPostId()).thenReturn(post.getId());
        lenient().when(identity.getAuthorUserId()).thenReturn(post.getAuthorUserId());
        lenient().when(identity.getAuthorPetId()).thenReturn(post.getAuthorPetId());
        lenient().when(identity.getStatus()).thenReturn(post.getStatus());
        return identity;
    }

    private BoardPostCommentRepository.ShareIdentity commentIdentity(BoardPostComment comment) {
        BoardPostCommentRepository.ShareIdentity identity = mock(BoardPostCommentRepository.ShareIdentity.class);
        lenient().when(identity.getCommentId()).thenReturn(comment.getId());
        lenient().when(identity.getPostId()).thenReturn(comment.getPostId());
        lenient().when(identity.getAuthorUserId()).thenReturn(comment.getAuthorUserId());
        lenient().when(identity.getAuthorPetId()).thenReturn(comment.getAuthorPetId());
        lenient().when(identity.getParentCommentId()).thenReturn(comment.getParentCommentId());
        lenient().when(identity.getRootCommentId()).thenReturn(comment.getRootCommentId());
        lenient().when(identity.getDepth()).thenReturn(comment.getDepth());
        lenient().when(identity.getDeletedAt()).thenReturn(comment.getDeletedAt());
        return identity;
    }

    private BoardPost post(long id, long authorUserId, long authorPetId) {
        BoardPost post = BoardPost.publish(
                7L, authorUserId, authorPetId, "4113111500", "title", "content"
        );
        ReflectionTestUtils.setField(post, "id", id);
        return post;
    }

    private BoardPostComment rootComment(long id, long postId, long authorUserId, long authorPetId) {
        BoardPostComment comment = BoardPostComment.create(postId, authorUserId, authorPetId, "content");
        ReflectionTestUtils.setField(comment, "id", id);
        return comment;
    }

    private User user(long id) {
        User user = User.register(
                "user" + id + "@test.com", "encoded", "user", "user#AB12", "4113111500"
        );
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode().name())
                .isEqualTo(code);
    }
}
