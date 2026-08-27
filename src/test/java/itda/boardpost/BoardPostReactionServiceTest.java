package itda.boardpost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import itda.block.service.BlockRelationshipQueryService;
import itda.board.repository.BoardRepository;
import itda.boardpost.domain.BoardPost;
import itda.boardpost.domain.BoardPostReactionType;
import itda.boardpost.domain.PostStatus;
import itda.boardpost.dto.BoardPostReactionSnapshot;
import itda.boardpost.dto.BoardPostCreateRequest;
import itda.boardpost.dto.BoardPostUpdateRequest;
import itda.boardpost.repository.BoardPostMediaRepository;
import itda.boardpost.repository.BoardPostReactionRepository;
import itda.boardpost.repository.BoardPostRepository;
import itda.boardpost.service.BoardPostReactionQueryService;
import itda.boardpost.service.BoardPostService;
import itda.boardpost.service.LockedActivePetCommandGuard;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.repository.MediaRepository;
import itda.media.service.MediaService;
import itda.notification.service.NotificationCommandService;
import itda.notification.domain.NotificationTargetType;
import itda.notification.domain.NotificationType;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BoardPostReactionServiceTest {

    @Mock private BoardPostRepository posts;
    @Mock private BoardPostMediaRepository postMedia;
    @Mock private BoardRepository boards;
    @Mock private UserRepository users;
    @Mock private LockedActivePetCommandGuard actorGuard;
    @Mock private PetDisplayQueryService petDisplays;
    @Mock private BlockRelationshipQueryService blocks;
    @Mock private MediaRepository media;
    @Mock private MediaService mediaService;
    @Mock private BoardPostReactionRepository reactions;
    @Mock private BoardPostReactionQueryService reactionQueries;
    @Mock private NotificationCommandService notificationCommandService;

    @Test
    void putAndDeleteUseIdempotentCommandsAndReturnObservedCount() {
        BoardPost post = post(101L, 2L, 20L, "4113111500");
        given(actorGuard.require(1L)).willReturn(actor(1L, 10L, "4113111500"));
        given(posts.findPublishedByIdForShare(101L)).willReturn(Optional.of(post));
        given(reactions.countForPost(101L, "LIKE")).willReturn(4L, 3L);

        var added = service().addReaction(1L, 101L, BoardPostReactionType.LIKE);
        var removed = service().removeReaction(1L, 101L, BoardPostReactionType.LIKE);

        assertThat(added.postId()).isEqualTo(101L);
        assertThat(added.type()).isEqualTo(BoardPostReactionType.LIKE);
        assertThat(added.reacted()).isTrue();
        assertThat(added.reactionCount()).isEqualTo(4);
        assertThat(removed.reacted()).isFalse();
        assertThat(removed.reactionCount()).isEqualTo(3);
        then(reactions).should().insertIgnore(101L, 10L, "LIKE");
        then(reactions).should().deleteReaction(101L, 10L, "LIKE");
    }

    @Test
    void reactionRejectsSelfPostOnlyAfterPublishedVisibilityIsResolved() {
        BoardPost selfPost = post(101L, 1L, 99L, "4113111500");
        given(actorGuard.require(1L)).willReturn(actor(1L, 10L, "4113111500"));
        given(posts.findPublishedByIdForShare(101L)).willReturn(Optional.of(selfPost));

        assertBusiness(() -> service().addReaction(1L, 101L, BoardPostReactionType.LIKE),
                ErrorCode.BOARD_POST_SELF_REACTION_FORBIDDEN);
        then(reactions).shouldHaveNoInteractions();
    }

    @Test
    void l1OrInvalidActivePetIsRejectedBeforePostVisibilityOrReactionCommand() {
        given(actorGuard.require(1L)).willThrow(new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED));

        assertBusiness(() -> service().addReaction(1L, 101L, BoardPostReactionType.LIKE),
                ErrorCode.ACTIVE_PET_REQUIRED);

        then(posts).shouldHaveNoInteractions();
        then(reactions).shouldHaveNoInteractions();
    }

    @Test
    void deletedOtherRegionAndBlockedPostsAreHiddenFromReactionMutation() {
        given(actorGuard.require(1L)).willReturn(actor(1L, 10L, "4113111500"));
        given(posts.findPublishedByIdForShare(101L)).willReturn(Optional.empty());
        assertBusiness(() -> service().addReaction(1L, 101L, BoardPostReactionType.LIKE),
                ErrorCode.BOARD_POST_NOT_FOUND);

        BoardPost otherRegion = post(102L, 2L, 20L, "4113351000");
        given(posts.findPublishedByIdForShare(102L)).willReturn(Optional.of(otherRegion));
        assertBusiness(() -> service().addReaction(1L, 102L, BoardPostReactionType.LIKE),
                ErrorCode.BOARD_POST_NOT_FOUND);

        BoardPost blocked = post(103L, 2L, 20L, "4113111500");
        given(posts.findPublishedByIdForShare(103L)).willReturn(Optional.of(blocked));
        given(blocks.existsBlockBetween(1L, 2L)).willReturn(true);
        assertBusiness(() -> service().addReaction(1L, 103L, BoardPostReactionType.LIKE),
                ErrorCode.BOARD_POST_NOT_FOUND);
        then(reactions).shouldHaveNoInteractions();
    }

    @Test
    void feedAssemblesReactionSnapshotsWithOneBatchCallAndCreateStaysZeroFalse() {
        User viewer = user(1L, "4113111500");
        BoardPost first = post(101L, 2L, 20L, "4113111500");
        BoardPost second = post(102L, 3L, 30L, "4113111500");
        given(users.findById(1L)).willReturn(Optional.of(viewer));
        given(boards.existsByIdAndDeletedAtIsNull(10L)).willReturn(true);
        given(posts.findVisibleFeed(10L, "4113111500", 1L, null, null, 21)).willReturn(List.of(first, second));
        given(petDisplays.getPetDisplaySummaries(List.of(20L, 30L))).willReturn(Map.of(
                20L, summary(20L), 30L, summary(30L)
        ));
        given(reactionQueries.findForPosts(1L, List.of(101L, 102L))).willReturn(Map.of(
                101L, new BoardPostReactionSnapshot(2, true),
                102L, new BoardPostReactionSnapshot(0, false)
        ));

        var result = service().feed(1L, 10L, null, null);

        assertThat(result.items()).extracting(item -> item.reactionCount()).containsExactly(2L, 0L);
        assertThat(result.items()).extracting(item -> item.reactedByMe()).containsExactly(true, false);
        assertThat(result.items()).extracting(item -> item.helpfulCount()).containsExactly(0L, 0L);
        assertThat(result.items()).extracting(item -> item.helpfulByMe()).containsExactly(false, false);
        then(reactionQueries).should().findForPosts(1L, List.of(101L, 102L));
        then(reactionQueries).shouldHaveNoMoreInteractions();
    }

    @Test
    void createReturnsZeroAndFalseWithoutAnyReactionQuery() {
        BoardPost created = post(101L, 1L, 10L, "4113111500");
        given(actorGuard.require(1L)).willReturn(actor(1L, 10L, "4113111500"));
        given(boards.findByIdForShare(10L)).willReturn(Optional.of(itda.board.domain.Board.create("board")));
        given(posts.save(org.mockito.ArgumentMatchers.any(BoardPost.class))).willReturn(created);
        given(petDisplays.getPetDisplaySummary(10L)).willReturn(summary(10L));

        var response = service().create(1L, 10L, new BoardPostCreateRequest("title", "content"));

        assertThat(response.reactionCount()).isZero();
        assertThat(response.reactedByMe()).isFalse();
        assertThat(response.helpfulCount()).isZero();
        assertThat(response.helpfulByMe()).isFalse();
        then(reactionQueries).shouldHaveNoInteractions();
    }

    @Test
    void detailAndPatchUseActualReactionCountButPatchNeverReportsReactedByAuthor() {
        BoardPost post = post(101L, 1L, 10L, "4113111500");
        given(users.findById(1L)).willReturn(Optional.of(user(1L, "4113111500")));
        given(posts.findByIdAndStatus(101L, PostStatus.PUBLISHED)).willReturn(Optional.of(post));
        given(petDisplays.getPetDisplaySummary(10L)).willReturn(summary(10L));
        given(reactionQueries.findForPost(1L, 101L)).willReturn(new BoardPostReactionSnapshot(5, true, 2, true));
        given(actorGuard.require(1L)).willReturn(actor(1L, 10L, "4113111500"));
        given(reactionQueries.countForPost(101L)).willReturn(5L);

        var detail = service().detail(1L, 101L);
        var patch = service().update(1L, 101L,
                new BoardPostUpdateRequest(true, "changed", false, null, 0));

        assertThat(detail.reactionCount()).isEqualTo(5);
        assertThat(detail.reactedByMe()).isTrue();
        assertThat(detail.helpfulCount()).isEqualTo(2);
        assertThat(detail.helpfulByMe()).isTrue();
        assertThat(patch.reactionCount()).isEqualTo(5);
        assertThat(patch.reactedByMe()).isFalse();
        assertThat(patch.helpfulCount()).isZero();
        assertThat(patch.helpfulByMe()).isFalse();
    }

    @Test
    void helpfulUsesTheSamePetActorButKeepsItsCountIndependentFromLike() {
        BoardPost post = post(101L, 2L, 20L, "4113111500");
        given(actorGuard.require(1L)).willReturn(actor(1L, 10L, "4113111500"));
        given(posts.findPublishedByIdForShare(101L)).willReturn(Optional.of(post));
        given(reactions.countForPost(101L, "HELPFUL")).willReturn(2L, 1L);

        var added = service().addReaction(1L, 101L, BoardPostReactionType.HELPFUL);
        var removed = service().removeReaction(1L, 101L, BoardPostReactionType.HELPFUL);

        assertThat(added.type()).isEqualTo(BoardPostReactionType.HELPFUL);
        assertThat(added.reactionCount()).isEqualTo(2L);
        assertThat(removed.reactionCount()).isEqualTo(1L);
        then(reactions).should().insertIgnore(101L, 10L, "HELPFUL");
        then(reactions).should().deleteReaction(101L, 10L, "HELPFUL");
        then(reactions).should(never()).insertIgnore(101L, 10L, "LIKE");
    }

    @Test
    void createsTypeSpecificPostReactionNotificationOnlyForNewReaction() {
        BoardPost post = post(101L, 2L, 20L, "4113111500");
        given(actorGuard.require(1L)).willReturn(actor(1L, 10L, "4113111500"));
        given(posts.findPublishedByIdForShare(101L)).willReturn(Optional.of(post));
        given(reactions.insertIgnore(101L, 10L, "HELPFUL")).willReturn(1);
        given(reactions.countForPost(101L, "HELPFUL")).willReturn(1L);
        given(petDisplays.getPetDisplaySummary(10L)).willReturn(summary(10L));
        given(petDisplays.getProfileAssetId(10L)).willReturn(555L);

        service().addReaction(1L, 101L, BoardPostReactionType.HELPFUL);

        then(notificationCommandService).should().notifyReaction(20L, 10L, "pet", 555L,
                NotificationType.BOARD_POST_HELPFUL, NotificationTargetType.BOARD_POST, 101L, 101L, null);
    }

    private void assertBusiness(ThrowingAction action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(expected);
    }

    private BoardPostService service() {
        return new BoardPostService(posts, postMedia, boards, users, actorGuard, petDisplays, blocks,
                media, mediaService, reactions, reactionQueries, notificationCommandService);
    }

    private LockedActivePetCommandGuard.LockedActor actor(long userId, long petId, String neighborhood) {
        return new LockedActivePetCommandGuard.LockedActor(userId, petId, neighborhood);
    }

    private BoardPost post(long id, long authorUserId, long authorPetId, String neighborhood) {
        BoardPost post = BoardPost.publish(10L, authorUserId, authorPetId, neighborhood, "title", "content");
        ReflectionTestUtils.setField(post, "id", id);
        ReflectionTestUtils.setField(post, "createdAt", Instant.parse("2026-08-10T00:00:00Z"));
        ReflectionTestUtils.setField(post, "updatedAt", Instant.parse("2026-08-10T00:00:00Z"));
        return post;
    }

    private User user(long id, String neighborhood) {
        User user = User.register("user" + id + "@test.com", "encoded", "user", "user#A1B2C3D4", neighborhood);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private PetDisplaySummary summary(long petId) {
        return new PetDisplaySummary(petId, 1L, "pet#A1B2", "pet", null, false, PetStatus.ACTIVE, null);
    }

    @FunctionalInterface
    private interface ThrowingAction { void run(); }
}
