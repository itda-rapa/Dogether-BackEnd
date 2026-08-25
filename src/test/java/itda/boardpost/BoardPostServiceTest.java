package itda.boardpost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import itda.block.service.BlockRelationshipQueryService;
import itda.board.repository.BoardRepository;
import itda.boardpost.domain.BoardPost;
import itda.boardpost.domain.BoardPostMedia;
import itda.boardpost.domain.PostStatus;
import itda.boardpost.dto.BoardPostCreateRequest;
import itda.boardpost.dto.BoardPostReactionSnapshot;
import itda.boardpost.repository.BoardPostMediaRepository;
import itda.boardpost.repository.BoardPostReactionRepository;
import itda.boardpost.repository.BoardPostRepository;
import itda.boardpost.service.BoardPostReactionQueryService;
import itda.boardpost.service.BoardPostService;
import itda.boardpost.service.LockedActivePetCommandGuard;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import itda.media.service.MediaService;
import itda.media.repository.MediaRepository;
import itda.common.exception.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BoardPostServiceTest {

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

    @Test
    void feedDeduplicatesAuthorPetIdsAndUsesOneBatchDisplayQuery() {
        BoardPostService service = service();
        User viewer = User.register("viewer@test.com", "encoded", "viewer", "viewer#A1B2C3D4", "4113111500");
        ReflectionTestUtils.setField(viewer, "id", 1L);
        given(users.findById(1L)).willReturn(Optional.of(viewer));
        given(boards.existsByIdAndDeletedAtIsNull(10L)).willReturn(true);
        BoardPost first = post(101L, 20L, 200L);
        BoardPost second = post(102L, 20L, 200L);
        BoardPost third = post(103L, 21L, 201L);
        given(posts.findVisibleFeed(10L, "4113111500", 1L, null, null, 21))
                .willReturn(List.of(first, second, third));
        given(petDisplays.getPetDisplaySummaries(org.mockito.ArgumentMatchers.anyCollection()))
                .willReturn(Map.of(200L, summary(200L), 201L, summary(201L)));
        given(reactionQueries.findForPosts(1L, List.of(101L, 102L, 103L))).willReturn(Map.of(
                101L, BoardPostReactionSnapshot.none(),
                102L, BoardPostReactionSnapshot.none(),
                103L, BoardPostReactionSnapshot.none()
        ));

        assertThat(service.feed(1L, 10L, null, null).items()).hasSize(3);
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        then(petDisplays).should().getPetDisplaySummaries(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(200L, 201L);
        then(petDisplays).shouldHaveNoMoreInteractions();
    }

    @Test
    void createBatchLoadsAttachmentsOnceAndPreservesRequestOrder() {
        BoardPostService service = service();
        LockedActivePetCommandGuard.LockedActor actor = new LockedActivePetCommandGuard.LockedActor(1L, 2L, "4113111500");
        BoardPost created = post(100L, 1L, 2L);
        itda.media.domain.Media first = media(30L, 1L);
        itda.media.domain.Media second = media(10L, 1L);
        given(actorGuard.require(1L)).willReturn(actor);
        given(boards.findByIdForShare(10L)).willReturn(Optional.of(itda.board.domain.Board.create("board")));
        given(media.findAllById(List.of(30L, 10L))).willReturn(List.of(second, first));
        given(posts.save(org.mockito.ArgumentMatchers.any(BoardPost.class))).willReturn(created);
        given(petDisplays.getPetDisplaySummary(2L)).willReturn(summary(2L));
        given(mediaService.getPresignedDownloadUrls(List.of(first, second)))
                .willReturn(downloadUrls(first, second));

        var response = service.create(1L, 10L, new BoardPostCreateRequest("title", "content", List.of(30L, 10L)));

        ArgumentCaptor<List<BoardPostMedia>> links = ArgumentCaptor.forClass(List.class);
        then(media).should().findAllById(List.of(30L, 10L));
        then(postMedia).should().saveAll(links.capture());
        assertThat(links.getValue()).extracting(BoardPostMedia::getMediaId).containsExactly(30L, 10L);
        assertThat(links.getValue()).extracting(BoardPostMedia::getDisplayOrder).containsExactly(0, 1);
        assertThat(response.images()).extracting(image -> image.mediaId()).containsExactly(30L, 10L);
    }

    @Test
    void createRejectsSoftDeletedBoardThroughActiveShareLookup() {
        BoardPostService service = service();
        given(actorGuard.require(1L)).willReturn(
                new LockedActivePetCommandGuard.LockedActor(1L, 2L, "4113111500")
        );
        given(boards.findByIdForShare(10L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(
                1L,
                10L,
                new BoardPostCreateRequest("title", "content")
        )).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(itda.common.constants.ErrorCode.BOARD_NOT_FOUND);

        then(posts).shouldHaveNoInteractions();
    }

    @Test
    void feedRejectsSoftDeletedBoardThroughActiveExistenceLookup() {
        BoardPostService service = service();
        User viewer = User.register(
                "viewer@test.com", "encoded", "viewer", "viewer#A1B2C3D4", "4113111500"
        );
        ReflectionTestUtils.setField(viewer, "id", 1L);
        given(users.findById(1L)).willReturn(Optional.of(viewer));
        given(boards.existsByIdAndDeletedAtIsNull(10L)).willReturn(false);

        assertThatThrownBy(() -> service.feed(1L, 10L, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(itda.common.constants.ErrorCode.BOARD_NOT_FOUND);

        then(posts).shouldHaveNoInteractions();
    }

    @Test
    void createAcceptsCompletedImageAndReturnsItsLinkImage() {
        BoardPostService service = service();
        itda.media.domain.Media completed = media(10L, 1L);
        ReflectionTestUtils.setField(completed, "status", itda.media.domain.MediaStatus.COMPLETED);
        prepareCreate();
        given(media.findAllById(List.of(10L))).willReturn(List.of(completed));
        given(posts.save(any(BoardPost.class))).willReturn(post(100L, 1L, 2L));
        given(petDisplays.getPetDisplaySummary(2L)).willReturn(summary(2L));
        given(mediaService.getPresignedDownloadUrls(List.of(completed)))
                .willReturn(Map.of(10L, new MediaService.PresignedDownloadUrl(
                        "https://example.test/media/10", Instant.now()
                )));

        var result = service.create(1L, 10L,
                new BoardPostCreateRequest("title", "content", List.of(10L)));

        assertThat(result.images()).singleElement().satisfies(image -> {
            assertThat(image.mediaId()).isEqualTo(10L);
            assertThat(image.url()).isEqualTo("https://example.test/media/10");
            assertThat(image.displayOrder()).isZero();
        });
        then(postMedia).should().saveAll(any());
    }

    @Test
    void feedUsesOneBatchAttachmentQueryAndSortsEachPostsImages() {
        BoardPostService service = service();
        User viewer = User.register("viewer@test.com", "encoded", "viewer", "viewer#A1B2C3D4", "4113111500");
        ReflectionTestUtils.setField(viewer, "id", 1L);
        BoardPost first = post(101L, 20L, 200L);
        BoardPost second = post(102L, 21L, 201L);
        given(users.findById(1L)).willReturn(Optional.of(viewer));
        given(boards.existsByIdAndDeletedAtIsNull(10L)).willReturn(true);
        given(posts.findVisibleFeed(10L, "4113111500", 1L, null, null, 21)).willReturn(List.of(first, second));
        given(petDisplays.getPetDisplaySummaries(org.mockito.ArgumentMatchers.anyCollection()))
                .willReturn(Map.of(200L, summary(200L), 201L, summary(201L)));
        given(postMedia.findByPostIdIn(List.of(101L, 102L))).willReturn(List.of(
                BoardPostMedia.attach(101L, 12L, 1), BoardPostMedia.attach(101L, 11L, 0)
        ));
        itda.media.domain.Media firstImage = media(11L, 20L);
        itda.media.domain.Media secondImage = media(12L, 21L);
        given(media.findAllById(any())).willReturn(List.of(firstImage, secondImage));
        given(mediaService.getPresignedDownloadUrls(List.of(firstImage, secondImage)))
                .willReturn(downloadUrls(firstImage, secondImage));
        given(reactionQueries.findForPosts(1L, List.of(101L, 102L))).willReturn(Map.of(
                101L, BoardPostReactionSnapshot.none(),
                102L, BoardPostReactionSnapshot.none()
        ));

        var result = service.feed(1L, 10L, null, null);

        then(postMedia).should().findByPostIdIn(List.of(101L, 102L));
        assertThat(result.items().getFirst().images()).extracting(image -> image.mediaId()).containsExactly(11L, 12L);
        assertThat(result.items().get(1).images()).isEmpty();
    }

    @Test
    void createAcceptsOmittedEmptyAndOneThroughFiveAttachments() {
        BoardPostService service = service();
        prepareCreate();
        given(media.findAllById(any())).willAnswer(invocation -> ((Iterable<Long>) invocation.getArgument(0)).iterator().hasNext()
                ? java.util.stream.StreamSupport.stream(((Iterable<Long>) invocation.getArgument(0)).spliterator(), false)
                .map(id -> media(id, 1L)).toList()
                : List.of());
        given(posts.save(any(BoardPost.class))).willReturn(post(100L, 1L, 2L));
        given(petDisplays.getPetDisplaySummary(2L)).willReturn(summary(2L));
        given(mediaService.getPresignedDownloadUrls(any())).willAnswer(invocation -> {
            Iterable<itda.media.domain.Media> attachments = invocation.getArgument(0);
            java.util.Map<Long, MediaService.PresignedDownloadUrl> urls = new java.util.HashMap<>();
            attachments.forEach(attachment -> urls.put(attachment.getId(),
                    new MediaService.PresignedDownloadUrl("url-" + attachment.getId(), Instant.now())));
            return urls;
        });

        assertThat(service.create(1L, 10L, new BoardPostCreateRequest("title", "content")).images()).isEmpty();
        assertThat(service.create(1L, 10L, new BoardPostCreateRequest("title", "content", List.of())).images()).isEmpty();
        for (int count = 1; count <= 5; count++) {
            List<Long> ids = java.util.stream.LongStream.rangeClosed(1, count).boxed().toList();
            assertThat(service.create(1L, 10L, new BoardPostCreateRequest("title", "content", ids)).images())
                    .extracting(image -> image.mediaId()).containsExactlyElementsOf(ids);
        }
        then(media).should(times(5)).findAllById(any());
    }

    @Test
    void createRejectsInvalidAttachmentsBeforePostOrLinkPersistence() {
        BoardPostService service = service();
        prepareCreate();
        List<itda.media.domain.Media> invalid = List.of(
                media(1L, 1L),
                media(2L, 1L),
                media(3L, 2L),
                media(4L, 1L),
                media(5L, 1L)
        );
        ReflectionTestUtils.setField(invalid.get(0), "status", itda.media.domain.MediaStatus.INIT);
        ReflectionTestUtils.setField(invalid.get(1), "status", itda.media.domain.MediaStatus.FAILED);
        ReflectionTestUtils.setField(invalid.get(3), "mediaType", itda.media.domain.MediaType.VIDEO);
        ReflectionTestUtils.setField(invalid.get(4), "deletedAt", Instant.now());
        for (itda.media.domain.Media attachment : invalid) {
            given(media.findAllById(List.of(attachment.getId()))).willReturn(List.of(attachment));
        }
        given(media.findAllById(List.of(999L))).willReturn(List.of());

        for (Long mediaId : List.of(1L, 2L, 3L, 4L, 5L, 999L)) {
            assertThatThrownBy(() -> service.create(1L, 10L,
                    new BoardPostCreateRequest("title", "content", List.of(mediaId))))
                    .isInstanceOf(BusinessException.class);
        }
        then(posts).shouldHaveNoInteractions();
        then(postMedia).shouldHaveNoInteractions();
    }

    @Test
    void detailAndPatchReturnExistingImagesWithoutChangingAttachments() {
        BoardPostService service = service();
        BoardPost post = post(101L, 1L, 2L);
        User viewer = User.register("viewer@test.com", "encoded", "viewer", "viewer#A1B2C3D4", "4113111500");
        ReflectionTestUtils.setField(viewer, "id", 1L);
        List<BoardPostMedia> links = List.of(
                BoardPostMedia.attach(101L, 11L, 0), BoardPostMedia.attach(101L, 12L, 1)
        );
        given(users.findById(1L)).willReturn(Optional.of(viewer));
        given(posts.findByIdAndStatus(101L, PostStatus.PUBLISHED)).willReturn(Optional.of(post));
        given(petDisplays.getPetDisplaySummary(2L)).willReturn(summary(2L));
        given(postMedia.findByPostIdOrderByDisplayOrderAsc(101L)).willReturn(links);
        itda.media.domain.Media firstImage = media(11L, 1L);
        itda.media.domain.Media secondImage = media(12L, 1L);
        given(media.findAllById(any())).willReturn(List.of(firstImage, secondImage));
        given(mediaService.getPresignedDownloadUrls(List.of(firstImage, secondImage)))
                .willReturn(downloadUrls(firstImage, secondImage));
        given(actorGuard.require(1L)).willReturn(new LockedActivePetCommandGuard.LockedActor(1L, 2L, "4113111500"));
        given(reactionQueries.findForPost(1L, 101L))
                .willReturn(BoardPostReactionSnapshot.none());

        assertThat(service.detail(1L, 101L).images()).extracting(image -> image.mediaId()).containsExactly(11L, 12L);
        assertThat(service.update(1L, 101L, new itda.boardpost.dto.BoardPostUpdateRequest(true, "changed", false, null, 0)).images())
                .extracting(image -> image.mediaId()).containsExactly(11L, 12L);
        then(postMedia).should(times(2)).findByPostIdOrderByDisplayOrderAsc(101L);
        then(postMedia).should(never()).saveAll(any());
        then(media).should(times(2)).findAllById(any());
        then(mediaService).should(times(2)).getPresignedDownloadUrls(List.of(firstImage, secondImage));
    }

    @Test
    void patchRejectsStaleVersionBeforeMediaLookupOrNoOpComparison() {
        BoardPostService service = service();
        BoardPost post = post(101L, 1L, 2L);
        ReflectionTestUtils.setField(post, "version", 2L);
        given(actorGuard.require(1L)).willReturn(
                new LockedActivePetCommandGuard.LockedActor(1L, 2L, "4113111500")
        );
        given(posts.findByIdAndStatus(101L, PostStatus.PUBLISHED)).willReturn(Optional.of(post));

        assertThatThrownBy(() -> service.update(1L, 101L,
                new itda.boardpost.dto.BoardPostUpdateRequest(
                        false, null, false, null, true, List.of(11L), 1L
                )))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(itda.common.constants.ErrorCode.CONCURRENT_UPDATE_CONFLICT);

        then(postMedia).shouldHaveNoInteractions();
        then(media).shouldHaveNoInteractions();
        then(mediaService).shouldHaveNoInteractions();
    }

    @Test
    void patchWithTheSameOrderedMediaIdsIsNoOpButStillValidatesAndSignsLoadedMedia() {
        BoardPostService service = service();
        BoardPost post = post(101L, 1L, 2L);
        List<BoardPostMedia> existing = List.of(
                BoardPostMedia.attach(101L, 11L, 0), BoardPostMedia.attach(101L, 12L, 1)
        );
        itda.media.domain.Media first = media(11L, 1L);
        itda.media.domain.Media second = media(12L, 1L);
        prepareUpdate(post);
        given(postMedia.findByPostIdOrderByDisplayOrderAsc(101L)).willReturn(existing);
        given(media.findAllById(List.of(11L, 12L))).willReturn(List.of(second, first));
        given(mediaService.getPresignedDownloadUrls(List.of(first, second)))
                .willReturn(downloadUrls(first, second));

        var response = service.update(1L, 101L,
                new itda.boardpost.dto.BoardPostUpdateRequest(
                        false, null, false, null, true, List.of(11L, 12L), 0L
                ));

        assertThat(response.images()).extracting(image -> image.mediaId())
                .containsExactly(11L, 12L);
        then(posts).should(never()).flush();
        then(postMedia).should(never()).deleteAll(any());
        then(postMedia).should(never()).flush();
        then(postMedia).should(never()).saveAll(any());
        then(media).should().findAllById(List.of(11L, 12L));
        then(mediaService).should().getPresignedDownloadUrls(List.of(first, second));
        then(mediaService).should(never()).getPresignedDownloadUrl(any());
    }

    @Test
    void patchReordersAttachmentsOnlyAfterTheParentOptimisticClaimAndReusesLoadedMedia() {
        BoardPostService service = service();
        BoardPost post = post(101L, 1L, 2L);
        List<BoardPostMedia> existing = List.of(
                BoardPostMedia.attach(101L, 11L, 0), BoardPostMedia.attach(101L, 12L, 1)
        );
        itda.media.domain.Media first = media(11L, 1L);
        itda.media.domain.Media second = media(12L, 1L);
        prepareUpdate(post);
        given(postMedia.findByPostIdOrderByDisplayOrderAsc(101L)).willReturn(existing);
        given(media.findAllById(List.of(12L, 11L))).willReturn(List.of(first, second));
        given(mediaService.getPresignedDownloadUrls(List.of(second, first)))
                .willReturn(downloadUrls(second, first));

        var response = service.update(1L, 101L,
                new itda.boardpost.dto.BoardPostUpdateRequest(
                        false, null, false, null, true, List.of(12L, 11L), 0L
                ));

        InOrder orderedDml = inOrder(posts, postMedia);
        orderedDml.verify(posts).flush();
        orderedDml.verify(postMedia).deleteAll(existing);
        orderedDml.verify(postMedia).flush();
        ArgumentCaptor<List<BoardPostMedia>> replacement = ArgumentCaptor.forClass(List.class);
        orderedDml.verify(postMedia).saveAll(replacement.capture());
        assertThat(replacement.getValue()).extracting(BoardPostMedia::getMediaId)
                .containsExactly(12L, 11L);
        assertThat(replacement.getValue()).extracting(BoardPostMedia::getDisplayOrder)
                .containsExactly(0, 1);
        assertThat(response.images()).extracting(image -> image.mediaId()).containsExactly(12L, 11L);
        then(media).should(times(1)).findAllById(List.of(12L, 11L));
        then(mediaService).should().getPresignedDownloadUrls(List.of(second, first));
        then(mediaService).should(never()).getPresignedDownloadUrl(any());
    }

    @Test
    void patchEmptyMediaIdsDeletesEveryLinkWithoutReinsertingOrSigning() {
        BoardPostService service = service();
        BoardPost post = post(101L, 1L, 2L);
        List<BoardPostMedia> existing = List.of(BoardPostMedia.attach(101L, 11L, 0));
        prepareUpdate(post);
        given(postMedia.findByPostIdOrderByDisplayOrderAsc(101L)).willReturn(existing);

        var response = service.update(1L, 101L,
                new itda.boardpost.dto.BoardPostUpdateRequest(
                        false, null, false, null, true, List.of(), 0L
                ));

        InOrder orderedDml = inOrder(posts, postMedia);
        orderedDml.verify(posts).flush();
        orderedDml.verify(postMedia).deleteAll(existing);
        orderedDml.verify(postMedia).flush();
        then(postMedia).should(never()).saveAll(any());
        assertThat(response.images()).isEmpty();
        then(media).shouldHaveNoInteractions();
        then(mediaService).shouldHaveNoInteractions();
    }

    @Test
    void patchWithOmittedMediaHydratesExistingLinksOnceAndUsesCollectionSigning() {
        BoardPostService service = service();
        BoardPost post = post(101L, 1L, 2L);
        List<BoardPostMedia> existing = List.of(
                BoardPostMedia.attach(101L, 11L, 0), BoardPostMedia.attach(101L, 12L, 1)
        );
        itda.media.domain.Media first = media(11L, 1L);
        itda.media.domain.Media second = media(12L, 1L);
        prepareUpdate(post);
        given(postMedia.findByPostIdOrderByDisplayOrderAsc(101L)).willReturn(existing);
        given(media.findAllById(any())).willReturn(List.of(first, second));
        given(mediaService.getPresignedDownloadUrls(List.of(first, second)))
                .willReturn(downloadUrls(first, second));

        var response = service.update(1L, 101L,
                new itda.boardpost.dto.BoardPostUpdateRequest(true, "title", false, null, 0L));

        assertThat(response.images()).extracting(image -> image.mediaId()).containsExactly(11L, 12L);
        then(postMedia).should(times(1)).findByPostIdOrderByDisplayOrderAsc(101L);
        then(media).should(times(1)).findAllById(any());
        then(mediaService).should(times(1)).getPresignedDownloadUrls(List.of(first, second));
        then(mediaService).should(never()).getPresignedDownloadUrl(any());
    }

    @Test
    void feedRejectsMissingHydratedMediaRatherThanReturningPartialImages() {
        BoardPostService service = service();
        User viewer = User.register("viewer@test.com", "encoded", "viewer", "viewer#A1B2C3D4", "4113111500");
        ReflectionTestUtils.setField(viewer, "id", 1L);
        BoardPost post = post(101L, 20L, 200L);
        given(users.findById(1L)).willReturn(Optional.of(viewer));
        given(boards.existsByIdAndDeletedAtIsNull(10L)).willReturn(true);
        given(posts.findVisibleFeed(10L, "4113111500", 1L, null, null, 21)).willReturn(List.of(post));
        given(petDisplays.getPetDisplaySummaries(List.of(200L))).willReturn(Map.of(200L, summary(200L)));
        given(postMedia.findByPostIdIn(List.of(101L))).willReturn(List.of(
                BoardPostMedia.attach(101L, 11L, 0), BoardPostMedia.attach(101L, 12L, 1)
        ));
        given(media.findAllById(any())).willReturn(List.of(media(11L, 20L)));

        assertThatThrownBy(() -> service.feed(1L, 10L, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        then(media).should(times(1)).findAllById(any());
        then(mediaService).shouldHaveNoInteractions();
    }

    @Test
    void softDeleteDoesNotTouchAttachmentsOrMedia() {
        BoardPostService service = service();
        BoardPost post = post(101L, 1L, 2L);
        given(actorGuard.require(1L)).willReturn(new LockedActivePetCommandGuard.LockedActor(1L, 2L, "4113111500"));
        given(posts.findPublishedByIdForUpdate(101L)).willReturn(Optional.of(post));

        service.delete(1L, 101L);

        assertThat(post.getStatus()).isEqualTo(PostStatus.DELETED);
        then(postMedia).shouldHaveNoInteractions();
        then(media).shouldHaveNoInteractions();
        then(mediaService).shouldHaveNoInteractions();
    }

    private BoardPost post(long id, long authorUserId, long petId) {
        BoardPost post = BoardPost.publish(10L, authorUserId, petId, "4113111500", "title", "content");
        ReflectionTestUtils.setField(post, "id", id);
        ReflectionTestUtils.setField(post, "createdAt", Instant.parse("2026-08-10T00:00:00Z"));
        ReflectionTestUtils.setField(post, "updatedAt", Instant.parse("2026-08-10T00:00:00Z"));
        return post;
    }

    private PetDisplaySummary summary(long id) {
        return new PetDisplaySummary(id, 1L, "pet#A1B2", "pet", null, false, PetStatus.ACTIVE, null);
    }

    private Map<Long, MediaService.PresignedDownloadUrl> downloadUrls(
            itda.media.domain.Media... attachments
    ) {
        Map<Long, MediaService.PresignedDownloadUrl> urls = new java.util.LinkedHashMap<>();
        for (itda.media.domain.Media attachment : attachments) {
            urls.put(attachment.getId(), new MediaService.PresignedDownloadUrl(
                    "url-" + attachment.getId(), Instant.now()
            ));
        }
        return urls;
    }

    private BoardPostService service() {
        return new BoardPostService(posts, postMedia, boards, users, actorGuard, petDisplays, blocks,
                media, mediaService, reactions, reactionQueries);
    }

    private itda.media.domain.Media media(long id, long userId) {
        itda.media.domain.Media media = new itda.media.domain.Media(itda.media.domain.MediaType.IMAGE, "image.jpg", userId, 1L);
        ReflectionTestUtils.setField(media, "id", id);
        ReflectionTestUtils.setField(media, "status", itda.media.domain.MediaStatus.UPLOADED);
        return media;
    }

    private void prepareCreate() {
        given(actorGuard.require(1L)).willReturn(new LockedActivePetCommandGuard.LockedActor(1L, 2L, "4113111500"));
        given(boards.findByIdForShare(10L)).willReturn(Optional.of(itda.board.domain.Board.create("board")));
    }

    private void prepareUpdate(BoardPost post) {
        given(actorGuard.require(1L)).willReturn(
                new LockedActivePetCommandGuard.LockedActor(1L, 2L, "4113111500")
        );
        given(posts.findByIdAndStatus(post.getId(), PostStatus.PUBLISHED)).willReturn(Optional.of(post));
        given(petDisplays.getPetDisplaySummary(2L)).willReturn(summary(2L));
    }
}
