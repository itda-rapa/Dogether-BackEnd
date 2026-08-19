package itda.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import itda.block.service.BlockRelationshipQueryService;
import itda.boardpost.domain.BoardPost;
import itda.boardpost.domain.PostStatus;
import itda.boardpost.repository.BoardPostRepository;
import itda.boardpost.service.LockedActivePetCommandGuard;
import itda.comment.domain.BoardPostComment;
import itda.comment.dto.CommentCreateRequest;
import itda.comment.dto.CommentUpdateRequest;
import itda.comment.repository.BoardPostCommentRepository;
import itda.comment.service.BoardPostCommentService;
import itda.common.exception.BusinessException;
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

        BoardPost otherRegion = publishedPost(10L, 20L, "4113111600");
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
        assertThat(result.page().hasNext()).isFalse();
        then(comments).should().findVisibleByPostId(10L, 1L, null, null, 3);
        then(petDisplays).should().getPetDisplaySummaries(List.of(20L, 21L));
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
        given(comments.findByIdAndDeletedAtIsNull(30L)).willReturn(Optional.of(owned));

        service().delete(1L, 30L);

        assertThat(owned.getDeletedAt()).isNotNull();
        then(posts).shouldHaveNoInteractions();

        BoardPostComment otherPet = comment(31L, 10L, 1L, 3L, "content", 0L);
        given(comments.findByIdAndDeletedAtIsNull(31L)).willReturn(Optional.of(otherPet));
        assertBusiness(() -> service().delete(1L, 31L), "BOARD_POST_COMMENT_FORBIDDEN");
        assertThat(otherPet.getDeletedAt()).isNull();
    }

    private BoardPostCommentService service() {
        return new BoardPostCommentService(comments, posts, users, actorGuard, petDisplays, blocks);
    }

    private LockedActivePetCommandGuard.LockedActor actor(long userId, long petId, String neighborhood) {
        return new LockedActivePetCommandGuard.LockedActor(userId, petId, neighborhood);
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
