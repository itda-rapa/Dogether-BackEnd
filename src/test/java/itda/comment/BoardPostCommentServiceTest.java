package itda.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import itda.block.service.BlockRelationshipQueryService;
import itda.boardpost.domain.BoardPost;
import itda.boardpost.domain.PostStatus;
import itda.boardpost.repository.BoardPostRepository;
import itda.boardpost.service.LockedActivePetCommandGuard;
import itda.comment.domain.BoardPostComment;
import itda.comment.domain.CommentReactionType;
import itda.comment.dto.CommentCreateRequest;
import itda.comment.dto.CommentReactionSnapshot;
import itda.comment.dto.CommentUpdateRequest;
import itda.comment.repository.BoardPostCommentRepository;
import itda.comment.repository.BoardPostCommentReactionRepository;
import itda.comment.service.BoardPostCommentService;
import itda.comment.service.CommentReactionQueryService;
import itda.common.exception.BusinessException;
import itda.notification.service.NotificationCommandService;
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
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BoardPostCommentServiceTest {

    @Mock private BoardPostCommentRepository comments;
    @Mock private BoardPostRepository posts;
    @Mock private UserRepository users;
    @Mock private LockedActivePetCommandGuard actorGuard;
    @Mock private PetDisplayQueryService petDisplays;
    @Mock private BlockRelationshipQueryService blocks;
    @Mock private BoardPostCommentReactionRepository reactions;
    @Mock private CommentReactionQueryService reactionQueries;
    @Mock private NotificationCommandService notificationCommandService;

    @Test
    void createSnapshotsTheLockedActorsUserAndActivePetAndPreservesContent() {
        BoardPost post = publishedPost(10L, 20L, "4113111500");
        BoardPostComment created = comment(30L, 10L, 1L, 2L, "  원문  ", 0L);
        given(actorGuard.require(1L)).willReturn(actor(1L, 2L, "4113111500"));
        given(posts.findPublishedByIdForShare(10L)).willReturn(Optional.of(post));
        given(blocks.existsBlockBetween(1L, 20L)).willReturn(false);
        given(comments.save(any(BoardPostComment.class))).willReturn(created);
        given(petDisplays.getPetDisplaySummary(2L)).willReturn(summary(2L));

        var response = service().create(1L, 10L, new CommentCreateRequest("  원문  "));

        ArgumentCaptor<BoardPostComment> saved = ArgumentCaptor.forClass(BoardPostComment.class);
        then(comments).should().save(saved.capture());
        assertThat(saved.getValue().getPostId()).isEqualTo(10L);
        assertThat(saved.getValue().getAuthorUserId()).isEqualTo(1L);
        assertThat(saved.getValue().getAuthorPetId()).isEqualTo(2L);
        assertThat(saved.getValue().getContent()).isEqualTo("  원문  ");
        assertThat(response.authorPet().petId()).isEqualTo(2L);
    }

    @Test
    void createRejectsInvalidUnicodeContentBeforeAcquiringAnyLocksOrWriting() {
        for (String content : List.of(" ", "\t\n", "😀".repeat(5001))) {
            assertBusiness(() -> service().create(1L, 10L, new CommentCreateRequest(content)), "VALIDATION_FAILED");
        }
        then(actorGuard).shouldHaveNoInteractions();
        then(posts).shouldHaveNoInteractions();
        then(comments).shouldHaveNoInteractions();
    }

    @Test
    void createTreatsHiddenOrDeletedParentAsBoardPostNotFound() {
        given(actorGuard.require(1L)).willReturn(actor(1L, 2L, "4113111500"));
        given(posts.findPublishedByIdForShare(10L)).willReturn(Optional.empty());
        assertBusiness(() -> service().create(1L, 10L, new CommentCreateRequest("content")), "BOARD_POST_NOT_FOUND");

        BoardPost otherRegion = publishedPost(10L, 20L, "4113351000");
        given(posts.findPublishedByIdForShare(10L)).willReturn(Optional.of(otherRegion));
        assertBusiness(() -> service().create(1L, 10L, new CommentCreateRequest("content")), "BOARD_POST_NOT_FOUND");
    }

    @Test
    void listUsesOneVisibilityLimitedPageThenBatchLoadsAuthorPets() {
        User viewer = activeUser(1L, "4113111500");
        BoardPost post = publishedPost(10L, 20L, "4113111500");
        BoardPostComment first = comment(31L, 10L, 2L, 20L, "first", 0L);
        BoardPostComment second = comment(32L, 10L, 3L, 21L, "second", 0L);
        given(users.findById(1L)).willReturn(Optional.of(viewer));
        given(posts.findByIdAndStatus(10L, PostStatus.PUBLISHED)).willReturn(Optional.of(post));
        given(blocks.existsBlockBetween(1L, 20L)).willReturn(false);
        given(comments.findVisibleByPostId(10L, 1L, null, null, 3)).willReturn(List.of(first, second));
        given(petDisplays.getPetDisplaySummaries(anyCollection())).willReturn(Map.of(
                20L, summary(20L), 21L, summary(21L)
        ));

        var result = service().list(1L, 10L, null, 2);

        assertThat(result.items()).extracting(item -> item.commentId()).containsExactly(31L, 32L);
        assertThat(result.items()).allSatisfy(item -> {
            assertThat(item.parentCommentId()).isNull();
            assertThat(item.depth()).isZero();
            assertThat(item.replies()).isEmpty();
        });
        assertThat(result.page().hasNext()).isFalse();
        then(comments).should().findVisibleByPostId(10L, 1L, null, null, 3);
        then(petDisplays).should().getPetDisplaySummaries(argThat(ids -> Set.copyOf(ids).equals(Set.of(20L, 21L))));
        then(blocks).should().findBlockedUserIdsBetween(1L, Set.of(2L, 3L));
    }

    @Test
    void createReplyUsesPathIdentityAndReturnsDirectParentAndDepthOnly() {
        BoardPost post = publishedPost(10L, 20L, "4113111500");
        BoardPostComment root = comment(30L, 10L, 2L, 20L, "root", 0L);
        BoardPostComment created = BoardPostComment.reply(10L, 1L, 3L, "reply", 30L, 30L, (short) 1);
        ReflectionTestUtils.setField(created, "id", 31L);
        ReflectionTestUtils.setField(created, "createdAt", Instant.parse("2026-08-10T00:00:01Z"));
        ReflectionTestUtils.setField(created, "updatedAt", Instant.parse("2026-08-10T00:00:01Z"));
        given(actorGuard.require(1L)).willReturn(actor(1L, 3L, "4113111500"));
        given(comments.findById(30L)).willReturn(Optional.of(root));
        given(posts.findPublishedByIdForShare(10L)).willReturn(Optional.of(post));
        given(comments.findActiveByIdForShare(30L)).willReturn(Optional.of(root));
        given(blocks.existsBlockBetween(1L, 20L)).willReturn(false);
        given(blocks.existsBlockBetween(1L, 2L)).willReturn(false);
        given(comments.save(any(BoardPostComment.class))).willReturn(created);
        given(petDisplays.getPetDisplaySummary(3L)).willReturn(summary(3L));

        var response = service().createReply(1L, 30L, new CommentCreateRequest("reply"));

        ArgumentCaptor<BoardPostComment> saved = ArgumentCaptor.forClass(BoardPostComment.class);
        then(comments).should().save(saved.capture());
        assertThat(saved.getValue().getParentCommentId()).isEqualTo(30L);
        assertThat(saved.getValue().getRootCommentId()).isEqualTo(30L);
        assertThat(saved.getValue().getDepth()).isEqualTo((short) 1);
        assertThat(response.parentCommentId()).isEqualTo(30L);
        assertThat(response.depth()).isEqualTo((short) 1);
        then(notificationCommandService).should().notifyCommentCreated(20L, 3L, "pet", 555L,
                NotificationType.BOARD_REPLY_CREATED, 31L, 10L, "reply");
    }

    @Test
    void rootCommentNotifiesPostAuthor() {
        BoardPost post = publishedPost(10L, 20L, "4113111500");
        BoardPostComment created = comment(30L, 10L, 1L, 2L, "comment", 0L);
        given(actorGuard.require(1L)).willReturn(actor(1L, 2L, "4113111500"));
        given(posts.findPublishedByIdForShare(10L)).willReturn(Optional.of(post));
        given(comments.save(any(BoardPostComment.class))).willReturn(created);
        given(petDisplays.getPetDisplaySummary(2L)).willReturn(summary(2L));

        service().create(1L, 10L, new CommentCreateRequest("comment"));

        then(notificationCommandService).should().notifyCommentCreated(20L, 2L, "pet", 555L,
                NotificationType.BOARD_COMMENT_CREATED, 30L, 10L, "comment");
    }

    @Test
    void createReplyHidesBlockedAncestorAndRejectsDepthFour() {
        BoardPost post = publishedPost(10L, 20L, "4113111500");
        BoardPostComment root = comment(30L, 10L, 2L, 20L, "root", 0L);
        BoardPostComment child = reply(31L, 10L, 3L, 21L, "child", 30L, 30L, (short) 1);
        BoardPostComment grandchild = reply(32L, 10L, 4L, 22L, "grandchild", 31L, 30L, (short) 2);
        BoardPostComment depthThree = reply(33L, 10L, 5L, 23L, "depth-three", 32L, 30L, (short) 3);
        given(actorGuard.require(1L)).willReturn(actor(1L, 3L, "4113111500"));
        given(comments.findById(31L)).willReturn(Optional.of(child));
        given(posts.findPublishedByIdForShare(10L)).willReturn(Optional.of(post));
        given(comments.findActiveByIdForShare(31L)).willReturn(Optional.of(child));
        given(comments.findById(30L)).willReturn(Optional.of(root));
        given(blocks.existsBlockBetween(1L, 20L)).willReturn(false);
        given(blocks.existsBlockBetween(1L, 2L)).willReturn(true, false);

        assertBusiness(() -> service().createReply(1L, 31L, new CommentCreateRequest("reply")), "BOARD_POST_COMMENT_NOT_FOUND");
        then(comments).should(never()).save(any());

        given(comments.findById(33L)).willReturn(Optional.of(depthThree));
        given(comments.findActiveByIdForShare(33L)).willReturn(Optional.of(depthThree));
        given(comments.findById(32L)).willReturn(Optional.of(grandchild));
        given(comments.findById(31L)).willReturn(Optional.of(child));
        assertBusiness(() -> service().createReply(1L, 33L, new CommentCreateRequest("too deep")), "COMMENT_DEPTH_EXCEEDED");
    }

    @Test
    void listBuildsTombstoneTreePrunesBlockedSubtreeAndUsesOnlyBatchQueries() {
        User viewer = activeUser(1L, "4113111500");
        BoardPost post = publishedPost(10L, 20L, "4113111500");
        BoardPostComment deletedRoot = comment(30L, 10L, 2L, 20L, "secret", 0L);
        deletedRoot.delete(Instant.now());
        BoardPostComment visibleReply = reply(31L, 10L, 3L, 21L, "visible", 30L, 30L, (short) 1);
        BoardPostComment blockedReply = reply(32L, 10L, 4L, 22L, "blocked", 30L, 30L, (short) 1);
        BoardPostComment hiddenDescendant = reply(33L, 10L, 5L, 23L, "must not promote", 32L, 30L, (short) 2);
        given(users.findById(1L)).willReturn(Optional.of(viewer));
        given(posts.findByIdAndStatus(10L, PostStatus.PUBLISHED)).willReturn(Optional.of(post));
        given(comments.findVisibleByPostId(10L, 1L, null, null, 2)).willReturn(List.of(deletedRoot));
        given(comments.findDescendantsByRootCommentIdIn(10L, List.of(30L)))
                .willReturn(List.of(visibleReply, blockedReply, hiddenDescendant));
        given(blocks.findBlockedUserIdsBetween(1L, Set.of(2L, 3L, 4L, 5L))).willReturn(Set.of(4L));
        given(petDisplays.getPetDisplaySummaries(anyCollection()))
                .willReturn(Map.of(21L, summary(21L)));
        given(reactionQueries.findForComments(1L, Set.of(31L)))
                .willReturn(Map.of(31L, new CommentReactionSnapshot(2L, true)));

        var result = fullService().list(1L, 10L, null, 1);

        assertThat(result.items()).hasSize(1);
        var tombstone = result.items().getFirst();
        assertThat(tombstone.deleted()).isTrue();
        assertThat(tombstone.content()).isNull();
        assertThat(tombstone.authorPet()).isNull();
        assertThat(tombstone.version()).isNull();
        assertThat(tombstone.helpfulCount()).isNull();
        assertThat(tombstone.helpfulByMe()).isNull();
        assertThat(tombstone.replies()).extracting(item -> item.commentId()).containsExactly(31L);
        assertThat(tombstone.replies().getFirst().helpfulCount()).isEqualTo(2L);
        assertThat(tombstone.replies().getFirst().helpfulByMe()).isTrue();
        then(blocks).should().existsBlockBetween(1L, 20L);
        then(comments).should().findDescendantsByRootCommentIdIn(10L, List.of(30L));
        then(petDisplays).should().getPetDisplaySummaries(argThat(ids -> Set.copyOf(ids).equals(Set.of(21L))));
        then(reactionQueries).should().findForComments(1L, Set.of(31L));
    }

    @Test
    void updateChecksVersionBeforeMutationAndSameContentDoesNotFlush() {
        BoardPostComment unchanged = comment(30L, 10L, 1L, 2L, "same", 4L);
        given(actorGuard.require(1L)).willReturn(actor(1L, 2L, "4113111500"));
        given(comments.findByIdAndDeletedAtIsNull(30L)).willReturn(Optional.of(unchanged));
        given(posts.findByIdAndStatus(10L, PostStatus.PUBLISHED)).willReturn(Optional.of(publishedPost(10L, 1L, "4113111500")));
        given(petDisplays.getPetDisplaySummary(2L)).willReturn(summary(2L));

        var response = service().update(1L, 30L, new CommentUpdateRequest("same", 4L));

        assertThat(response.version()).isEqualTo(4L);
        assertThat(unchanged.getContent()).isEqualTo("same");
        then(comments).should(never()).flush();

        assertBusiness(() -> service().update(1L, 30L, new CommentUpdateRequest("changed", 3L)), "CONCURRENT_UPDATE_CONFLICT");
        assertThat(unchanged.getContent()).isEqualTo("same");
        then(comments).should(never()).flush();
    }

    @Test
    void deleteDoesNotRequirePublishedParentButStillRequiresOriginalUserAndActivePet() {
        BoardPostComment owned = comment(30L, 10L, 1L, 2L, "content", 0L);
        given(actorGuard.require(1L)).willReturn(actor(1L, 2L, "4113111500"));
        given(comments.findActiveByIdForUpdate(30L)).willReturn(Optional.of(owned));

        service().delete(1L, 30L);

        assertThat(owned.getDeletedAt()).isNotNull();
        then(posts).shouldHaveNoInteractions();

        BoardPostComment otherPet = comment(31L, 10L, 1L, 3L, "content", 0L);
        given(comments.findActiveByIdForUpdate(31L)).willReturn(Optional.of(otherPet));
        assertBusiness(() -> service().delete(1L, 31L), "BOARD_POST_COMMENT_FORBIDDEN");
        assertThat(otherPet.getDeletedAt()).isNull();
    }

    @Test
    void helpfulPutAndDeleteForRootAreIdempotentCommandsWithTypeSpecificCounts() {
        BoardPost post = publishedPost(10L, 20L, "4113111500");
        BoardPostComment root = comment(30L, 10L, 2L, 20L, "root", 0L);
        reactionTarget(1L, 30L, post, root);
        given(reactions.countForComment(30L, "HELPFUL")).willReturn(2L, 1L);

        var added = fullService().addReaction(1L, 30L, CommentReactionType.HELPFUL);
        var removed = fullService().removeReaction(1L, 30L, CommentReactionType.HELPFUL);

        assertThat(added.reacted()).isTrue();
        assertThat(added.reactionCount()).isEqualTo(2L);
        assertThat(removed.reacted()).isFalse();
        assertThat(removed.reactionCount()).isEqualTo(1L);
        then(reactions).should().insertIgnore(30L, 3L, "HELPFUL");
        then(reactions).should().deleteReaction(30L, 3L, "HELPFUL");
    }

    @Test
    void helpfulReactionSupportsRepliesButRejectsSameUserEvenAfterActivePetSwitch() {
        BoardPost post = publishedPost(10L, 20L, "4113111500");
        BoardPostComment root = comment(30L, 10L, 2L, 20L, "root", 0L);
        BoardPostComment reply = reply(31L, 10L, 2L, 21L, "reply", 30L, 30L, (short) 1);
        reactionTarget(1L, 31L, post, reply);
        given(comments.findById(30L)).willReturn(Optional.of(root));
        given(reactions.countForComment(31L, "HELPFUL")).willReturn(1L);

        var response = fullService().addReaction(1L, 31L, CommentReactionType.HELPFUL);

        assertThat(response.commentId()).isEqualTo(31L);
        then(reactions).should().insertIgnore(31L, 3L, "HELPFUL");

        BoardPostComment ownComment = comment(32L, 10L, 1L, 99L, "own", 0L);
        reactionTarget(1L, 32L, post, ownComment);
        assertBusiness(() -> fullService().addReaction(1L, 32L, CommentReactionType.HELPFUL),
                "BOARD_POST_COMMENT_SELF_REACTION_FORBIDDEN");
        then(reactions).should(never()).insertIgnore(32L, 3L, "HELPFUL");
    }

    @Test
    void helpfulReactionHidesBlockedAncestorAndDoesNotMutate() {
        BoardPost post = publishedPost(10L, 20L, "4113111500");
        BoardPostComment root = comment(30L, 10L, 2L, 20L, "root", 0L);
        BoardPostComment reply = reply(31L, 10L, 3L, 21L, "reply", 30L, 30L, (short) 1);
        given(actorGuard.require(1L)).willReturn(actor(1L, 4L, "4113111500"));
        given(comments.findById(31L)).willReturn(Optional.of(reply));
        given(posts.findPublishedByIdForShare(10L)).willReturn(Optional.of(post));
        given(comments.findActiveByIdForShare(31L)).willReturn(Optional.of(reply));
        given(comments.findById(30L)).willReturn(Optional.of(root));
        given(blocks.existsBlockBetween(1L, 20L)).willReturn(false);
        given(blocks.existsBlockBetween(1L, 2L)).willReturn(true);

        assertBusiness(() -> fullService().addReaction(1L, 31L, CommentReactionType.HELPFUL),
                "BOARD_POST_COMMENT_NOT_FOUND");
        then(reactions).shouldHaveNoInteractions();
    }

    private BoardPostCommentService service() {
        return fullService();
    }

    private BoardPostCommentService fullService() {
        return new BoardPostCommentService(
                comments, posts, users, actorGuard, petDisplays, blocks, reactions, reactionQueries,
                notificationCommandService
        );
    }

    private void reactionTarget(
            long actorUserId,
            long commentId,
            BoardPost post,
            BoardPostComment target
    ) {
        given(actorGuard.require(actorUserId)).willReturn(actor(actorUserId, 3L, "4113111500"));
        given(comments.findById(commentId)).willReturn(Optional.of(target));
        given(posts.findPublishedByIdForShare(target.getPostId())).willReturn(Optional.of(post));
        given(comments.findActiveByIdForShare(commentId)).willReturn(Optional.of(target));
        given(blocks.existsBlockBetween(actorUserId, post.getAuthorUserId())).willReturn(false);
        given(blocks.existsBlockBetween(actorUserId, target.getAuthorUserId())).willReturn(false);
    }

    private LockedActivePetCommandGuard.LockedActor actor(long userId, long petId, String neighborhood) {
        return new LockedActivePetCommandGuard.LockedActor(userId, petId, neighborhood, "pet", 555L);
    }

    private User activeUser(long id, String neighborhood) {
        User user = User.register("user" + id + "@test.com", "encoded", "user", "user#A1B2C3D4", neighborhood);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private BoardPost publishedPost(long id, long authorId, String neighborhood) {
        BoardPost post = BoardPost.publish(1L, authorId, 20L, neighborhood, "title", "content");
        ReflectionTestUtils.setField(post, "id", id);
        return post;
    }

    private BoardPostComment comment(long id, long postId, long authorUserId, long authorPetId, String content, long version) {
        BoardPostComment comment = BoardPostComment.create(postId, authorUserId, authorPetId, content);
        ReflectionTestUtils.setField(comment, "id", id);
        ReflectionTestUtils.setField(comment, "version", version);
        ReflectionTestUtils.setField(comment, "createdAt", Instant.parse("2026-08-10T00:00:00Z"));
        ReflectionTestUtils.setField(comment, "updatedAt", Instant.parse("2026-08-10T00:00:00Z"));
        return comment;
    }

    private BoardPostComment reply(long id, long postId, long authorUserId, long authorPetId, String content,
            long parentCommentId, long rootCommentId, short depth) {
        BoardPostComment comment = BoardPostComment.reply(
                postId, authorUserId, authorPetId, content, parentCommentId, rootCommentId, depth
        );
        ReflectionTestUtils.setField(comment, "id", id);
        ReflectionTestUtils.setField(comment, "createdAt", Instant.parse("2026-08-10T00:00:00Z").plusSeconds(id));
        ReflectionTestUtils.setField(comment, "updatedAt", Instant.parse("2026-08-10T00:00:00Z").plusSeconds(id));
        return comment;
    }

    private PetDisplaySummary summary(long petId) {
        return new PetDisplaySummary(petId, 1L, "pet#A1B2", "pet", null, false, PetStatus.ACTIVE, null);
    }

    private void assertBusiness(ThrowingAction action, String code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode().name())
                .isEqualTo(code);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
