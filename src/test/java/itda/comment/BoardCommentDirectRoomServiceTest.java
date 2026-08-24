package itda.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import itda.block.service.BlockRelationshipQueryService;
import itda.boardpost.domain.BoardPost;
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
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.user.domain.AccountStatus;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BoardCommentDirectRoomServiceTest {

    @Mock private BoardPostRepository posts;
    @Mock private BoardPostCommentRepository comments;
    @Mock private UserRepository users;
    @Mock private ActivePetQueryService activePets;
    @Mock private InteractionPairLockService pairLocks;
    @Mock private BlockRelationshipQueryService blocks;
    @Mock private ChatRoomService chatRooms;

    private final InteractionTargetQueryService targets =
            new InteractionTargetQueryService();

    @Test
    void connectsAVisibleRootCommentAndReusesTheExistingChatContract() {
        BoardPost post = post(10L, 1L, 11L);
        BoardPostComment comment = rootComment(20L, 10L, 2L, 22L);
        given(activePets.requireActivePet(1L)).willReturn(context(1L, 11L));
        given(posts.findPublishedByIdForShare(10L)).willReturn(Optional.of(post));
        given(comments.findActiveByIdForShare(20L)).willReturn(Optional.of(comment));
        given(users.findById(1L)).willReturn(Optional.of(user(1L)));
        given(pairLocks.lockInteractionPair(11L, 22L))
                .willReturn(pair(11L, 1L, 22L, 2L));
        given(blocks.existsBlockBetween(1L, 2L)).willReturn(false);
        given(chatRooms.ensureDirectRoom(11L, 22L, RoomOrigin.FRIEND))
                .willReturn(new EnsureDirectRoomResult(99L, false));

        EnsureDirectRoomResult result = service().ensureDirectRoom(1L, 10L, 20L);

        assertThat(result).isEqualTo(new EnsureDirectRoomResult(99L, false));
        then(pairLocks).should().lockInteractionPair(11L, 22L);
        then(chatRooms).should().ensureDirectRoom(11L, 22L, RoomOrigin.FRIEND);
    }

    @Test
    void allowsTheCommentAuthorPetToStartTheConnection() {
        BoardPost post = post(10L, 1L, 11L);
        BoardPostComment comment = rootComment(20L, 10L, 2L, 22L);
        given(activePets.requireActivePet(2L)).willReturn(context(2L, 22L));
        given(posts.findPublishedByIdForShare(10L)).willReturn(Optional.of(post));
        given(comments.findActiveByIdForShare(20L)).willReturn(Optional.of(comment));
        given(users.findById(2L)).willReturn(Optional.of(user(2L)));
        given(pairLocks.lockInteractionPair(11L, 22L))
                .willReturn(pair(11L, 1L, 22L, 2L));
        given(blocks.existsBlockBetween(1L, 2L)).willReturn(false);
        given(chatRooms.ensureDirectRoom(11L, 22L, RoomOrigin.FRIEND))
                .willReturn(new EnsureDirectRoomResult(99L, true));

        assertThat(service().ensureDirectRoom(2L, 10L, 20L).isNew()).isTrue();
    }

    @Test
    void rejectsRepliesBeforeCallingChatCore() {
        BoardPost post = post(10L, 1L, 11L);
        BoardPostComment reply = BoardPostComment.reply(
                10L, 2L, 22L, "reply", 19L, 19L, (short) 1
        );
        ReflectionTestUtils.setField(reply, "id", 20L);
        given(activePets.requireActivePet(1L)).willReturn(context(1L, 11L));
        given(posts.findPublishedByIdForShare(10L)).willReturn(Optional.of(post));
        given(comments.findActiveByIdForShare(20L)).willReturn(Optional.of(reply));

        assertCode(() -> service().ensureDirectRoom(1L, 10L, 20L), "BOARD_POST_COMMENT_NOT_FOUND");
        then(chatRooms).shouldHaveNoInteractions();
    }

    @Test
    void rejectsAThirdPartyActivePetWithoutTouchingChatCore() {
        given(activePets.requireActivePet(3L)).willReturn(context(3L, 33L));
        given(posts.findPublishedByIdForShare(10L)).willReturn(Optional.of(post(10L, 1L, 11L)));
        given(comments.findActiveByIdForShare(20L)).willReturn(Optional.of(rootComment(20L, 10L, 2L, 22L)));

        assertCode(() -> service().ensureDirectRoom(3L, 10L, 20L), "FORBIDDEN");
        then(users).shouldHaveNoInteractions();
        then(chatRooms).shouldHaveNoInteractions();
    }

    @Test
    void rejectsSamePetSameOwnerAndBlockWithExistingPolicies() {
        BoardPost samePetPost = post(10L, 1L, 11L);
        BoardPostComment samePet = rootComment(20L, 10L, 1L, 11L);
        given(activePets.requireActivePet(1L)).willReturn(context(1L, 11L));
        given(posts.findPublishedByIdForShare(10L)).willReturn(Optional.of(samePetPost));
        given(comments.findActiveByIdForShare(20L)).willReturn(Optional.of(samePet));
        given(users.findById(1L)).willReturn(Optional.of(user(1L)));
        assertCode(() -> service().ensureDirectRoom(1L, 10L, 20L), "CHAT_ROOM_SAME_PET_FORBIDDEN");
        then(pairLocks).shouldHaveNoInteractions();

        BoardPostComment sameOwner = rootComment(21L, 10L, 1L, 22L);
        given(comments.findActiveByIdForShare(21L)).willReturn(Optional.of(sameOwner));
        given(pairLocks.lockInteractionPair(11L, 22L)).willReturn(pair(11L, 1L, 22L, 1L));
        assertCode(() -> service().ensureDirectRoom(1L, 10L, 21L), "SAME_OWNER_INTERACTION_FORBIDDEN");

        BoardPostComment blocked = rootComment(22L, 10L, 2L, 22L);
        given(comments.findActiveByIdForShare(22L)).willReturn(Optional.of(blocked));
        given(pairLocks.lockInteractionPair(11L, 22L)).willReturn(pair(11L, 1L, 22L, 2L));
        given(blocks.existsBlockBetween(1L, 2L)).willReturn(true);
        assertCode(() -> service().ensureDirectRoom(1L, 10L, 22L), "CHAT_ROOM_NOT_FOUND");
        then(chatRooms).shouldHaveNoInteractions();
    }

    @Test
    void hidesASuspendedTargetPetFromTheLockedSnapshot() {
        BoardPost post = post(10L, 1L, 11L);
        BoardPostComment comment = rootComment(20L, 10L, 2L, 22L);
        stubBoardAndLock(post, comment, pair(
                11L, 1L, PetStatus.ACTIVE, null, AccountStatus.ACTIVE,
                22L, 2L, PetStatus.SUSPENDED, null, AccountStatus.ACTIVE
        ));

        assertCode(() -> service().ensureDirectRoom(1L, 10L, 20L), "CHAT_ROOM_NOT_FOUND");
        then(chatRooms).shouldHaveNoInteractions();
    }

    @Test
    void hidesADeletedTargetPetFromTheLockedSnapshot() {
        BoardPost post = post(10L, 1L, 11L);
        BoardPostComment comment = rootComment(20L, 10L, 2L, 22L);
        stubBoardAndLock(post, comment, pair(
                11L, 1L, PetStatus.ACTIVE, null, AccountStatus.ACTIVE,
                22L, 2L, PetStatus.DELETED, Instant.parse("2024-01-01T00:00:00Z"), AccountStatus.ACTIVE
        ));

        assertCode(() -> service().ensureDirectRoom(1L, 10L, 20L), "CHAT_ROOM_NOT_FOUND");
        then(chatRooms).shouldHaveNoInteractions();
    }

    @Test
    void hidesAnInactiveTargetOwnerFromTheLockedSnapshot() {
        BoardPost post = post(10L, 1L, 11L);
        BoardPostComment comment = rootComment(20L, 10L, 2L, 22L);
        stubBoardAndLock(post, comment, pair(
                11L, 1L, PetStatus.ACTIVE, null, AccountStatus.ACTIVE,
                22L, 2L, PetStatus.ACTIVE, null, AccountStatus.SUSPENDED
        ));

        assertCode(() -> service().ensureDirectRoom(1L, 10L, 20L), "CHAT_ROOM_NOT_FOUND");
        then(chatRooms).shouldHaveNoInteractions();
    }

    @Test
    void hidesASuspendedSourcePetFromTheLockedSnapshot() {
        BoardPost post = post(10L, 1L, 11L);
        BoardPostComment comment = rootComment(20L, 10L, 2L, 22L);
        stubBoardAndLock(post, comment, pair(
                11L, 1L, PetStatus.SUSPENDED, null, AccountStatus.ACTIVE,
                22L, 2L, PetStatus.ACTIVE, null, AccountStatus.ACTIVE
        ));

        assertCode(() -> service().ensureDirectRoom(1L, 10L, 20L), "CHAT_ROOM_NOT_FOUND");
        then(chatRooms).shouldHaveNoInteractions();
    }

    @Test
    void hidesAnInactiveSourceOwnerFromTheLockedSnapshot() {
        BoardPost post = post(10L, 1L, 11L);
        BoardPostComment comment = rootComment(20L, 10L, 2L, 22L);
        stubBoardAndLock(post, comment, pair(
                11L, 1L, PetStatus.ACTIVE, null, AccountStatus.WITHDRAWN,
                22L, 2L, PetStatus.ACTIVE, null, AccountStatus.ACTIVE
        ));

        assertCode(() -> service().ensureDirectRoom(1L, 10L, 20L), "CHAT_ROOM_NOT_FOUND");
        then(chatRooms).shouldHaveNoInteractions();
    }

    @Test
    void hidesAPostOwnerThatNoLongerOwnsTheLockedPostPet() {
        BoardPost post = post(10L, 1L, 11L);
        BoardPostComment comment = rootComment(20L, 10L, 2L, 22L);
        stubBoardAndLock(post, comment, pair(
                11L, 99L, PetStatus.ACTIVE, null, AccountStatus.ACTIVE,
                22L, 2L, PetStatus.ACTIVE, null, AccountStatus.ACTIVE
        ));

        assertCode(() -> service().ensureDirectRoom(1L, 10L, 20L), "CHAT_ROOM_NOT_FOUND");
        then(chatRooms).shouldHaveNoInteractions();
    }

    @Test
    void hidesACommentOwnerThatNoLongerOwnsTheLockedCommentPet() {
        BoardPost post = post(10L, 1L, 11L);
        BoardPostComment comment = rootComment(20L, 10L, 2L, 22L);
        stubBoardAndLock(post, comment, pair(
                11L, 1L, PetStatus.ACTIVE, null, AccountStatus.ACTIVE,
                22L, 99L, PetStatus.ACTIVE, null, AccountStatus.ACTIVE
        ));

        assertCode(() -> service().ensureDirectRoom(1L, 10L, 20L), "CHAT_ROOM_NOT_FOUND");
        then(chatRooms).shouldHaveNoInteractions();
    }

    @Test
    void rejectsDeletedOrMismatchedBoardResourcesThroughExistingBoardErrors() {
        given(activePets.requireActivePet(1L)).willReturn(context(1L, 11L));
        given(posts.findPublishedByIdForShare(10L)).willReturn(Optional.empty());
        assertCode(() -> service().ensureDirectRoom(1L, 10L, 20L), "BOARD_POST_NOT_FOUND");

        given(posts.findPublishedByIdForShare(10L)).willReturn(Optional.of(post(10L, 1L, 11L)));
        given(comments.findActiveByIdForShare(20L)).willReturn(Optional.of(rootComment(20L, 99L, 2L, 22L)));
        assertCode(() -> service().ensureDirectRoom(1L, 10L, 20L), "BOARD_POST_COMMENT_NOT_FOUND");
    }

    private void stubBoardAndLock(
            BoardPost post,
            BoardPostComment comment,
            InteractionPairContext lockedPair
    ) {
        given(activePets.requireActivePet(1L)).willReturn(context(1L, 11L));
        given(posts.findPublishedByIdForShare(10L)).willReturn(Optional.of(post));
        given(comments.findActiveByIdForShare(20L)).willReturn(Optional.of(comment));
        given(users.findById(1L)).willReturn(Optional.of(user(1L)));
        given(pairLocks.lockInteractionPair(11L, 22L)).willReturn(lockedPair);
    }

    private BoardCommentDirectRoomService service() {
        return new BoardCommentDirectRoomService(
                posts, comments, users, activePets, targets, pairLocks, blocks, chatRooms
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

    private ActivePetContext context(long userId, long petId) {
        return new ActivePetContext(petId, userId, "pet#AB12", "pet", null, false);
    }

    private User user(long id) {
        User user = User.register(
                "user" + id + "@test.com", "encoded", "user", "user#AB12", "4113111500"
        );
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private void assertCode(Runnable action, String code) {
        assertThatThrownBy(() -> action.run())
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode().name())
                .isEqualTo(code);
    }
}
