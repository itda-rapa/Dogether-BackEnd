package itda.boardpost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import itda.boardpost.domain.BoardPost;
import itda.boardpost.dto.BoardPostCreateRequest;
import itda.boardpost.dto.BoardPostResponse;
import itda.boardpost.dto.BoardPostUpdateRequest;
import itda.boardpost.domain.BoardPostMedia;
import itda.boardpost.repository.BoardPostMediaRepository;
import itda.boardpost.repository.BoardPostRepository;
import itda.boardpost.service.BoardPostService;
import itda.board.repository.BoardRepository;
import itda.board.service.BoardDeletionService;
import itda.common.exception.BusinessException;
import itda.media.repository.MediaRepository;
import itda.pet.service.ActivePetSelectionService;
import itda.pet.service.query.PetDisplayQueryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class BoardPostPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                        .asCompatibleSubstituteFor("postgres")
        );

    @Autowired private JdbcTemplate jdbc;
    @Autowired private BoardPostRepository posts;
    @Autowired private BoardPostMediaRepository postMedia;
    @Autowired private BoardRepository boards;
    @Autowired private BoardPostService postService;
    @Autowired private BoardDeletionService boardDeletionService;
    @Autowired private ActivePetSelectionService activePetSelectionService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PetDisplayQueryService petDisplays;
    @Autowired private jakarta.persistence.EntityManagerFactory entityManagerFactory;
    @MockitoSpyBean private MediaRepository mediaRepository;

    @Test
    void flywayCreatesBoardPostConstraintsAndRequiredIndexes() {
        long boardId = jdbc.queryForObject("insert into boards (name) values (?) returning id", Long.class, unique("board"));
        long userId = jdbc.queryForObject("""
                insert into users (email, password_hash, nickname, public_tag, role, account_status, neighborhood_code)
                values (?, 'encoded', '작성자', ?, 'USER', 'ACTIVE', '4113165000') returning id
                """, Long.class, unique("user") + "@example.com", publicTag("작성자", 8));
        long petId = jdbc.queryForObject("""
                insert into pets (owner_user_id, public_tag, nickname, status)
                values (?, ?, '반려견', 'ACTIVE') returning id
                """, Long.class, userId, publicTag("반려견", 4));

        assertThatThrownBy(() -> jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113165000', ' ', '내용', 'PUBLISHED')
                """, boardId, userId, petId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status, deleted_at)
                values (?, ?, ?, '4113165000', '제목', '내용', 'PUBLISHED', now())
                """, boardId, userId, petId)).isInstanceOf(DataIntegrityViolationException.class);

        String feedIndex = jdbc.queryForObject("""
                select indexdef from pg_indexes
                where schemaname = current_schema() and indexname = 'ix_board_posts_published_feed'
                """, String.class);
        assertThat(feedIndex).contains("board_id, neighborhood_code, created_at DESC, id DESC")
                .contains("PUBLISHED");
        assertThat(jdbc.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = current_schema() and indexname = 'ix_board_posts_board_id'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void databaseEnforcesEveryForeignKeyAndContentStatusInvariants() {
        long board = createBoard();
        Author author = createAuthor("constraints", "4113165000");
        Object[] base = {board, author.userId(), author.petId()};
        assertThatThrownBy(() -> jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113165000', 'title', 'content', 'PUBLISHED')
                """, 999999999L, author.userId(), author.petId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113165000', 'title', 'content', 'PUBLISHED')
                """, board, 999999999L, author.petId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113165000', 'title', 'content', 'PUBLISHED')
                """, board, author.userId(), 999999999L)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, 'INVALID', 'title', 'content', 'PUBLISHED')
                """, base)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113165000', 'title', ' ', 'PUBLISHED')
                """, base)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113165000', 'title', ?, 'PUBLISHED')
                """, board, author.userId(), author.petId(), "가".repeat(5001))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113165000', 'title', 'content', 'DRAFT')
                """, base)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113165000', 'title', 'content', 'DELETED')
                """, base)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113165000', 'title', ?, 'PUBLISHED')
                """, board, author.userId(), author.petId(), "가".repeat(5000));
    }

    @Test
    void postServiceAcceptsMaximumUnicodeCodePointTitleAndContent() {
        long board = createBoard();
        Author author = createAuthor("unicode", "4113165000");
        jdbc.update("update users set active_pet_id = ? where id = ?", author.petId(), author.userId());
        BoardPostResponse result = postService.create(author.userId(), board,
                new BoardPostCreateRequest("😀".repeat(120), "가".repeat(5000)));
        assertThat(result.title().codePointCount(0, result.title().length())).isEqualTo(120);
        assertThat(result.content()).hasSize(5000);
    }

    @Test
    void feedReturnsEmptyPageAndValidatesBoardCursorSizeAndLimitPlusOne() {
        long board = createBoard();
        long viewer = createUser("feedViewer", "4113165000");
        assertThat(postService.feed(viewer, board, null, null).items()).isEmpty();
        assertThat(postService.feed(viewer, board, null, null).page().hasNext()).isFalse();
        assertBusiness(() -> postService.feed(viewer, 999999999L, null, null), "BOARD_NOT_FOUND");
        assertBusiness(() -> postService.feed(viewer, board, "malformed", null), "VALIDATION_FAILED");
        assertBusiness(() -> postService.feed(viewer, board, null, 0), "VALIDATION_FAILED");
        assertBusiness(() -> postService.feed(viewer, board, null, 101), "VALIDATION_FAILED");

        Author author = createAuthor("feedAuthor", "4113165000");
        Instant now = Instant.parse("2026-08-10T01:00:00Z");
        for (int index = 0; index < 21; index++) {
            insertPost(board, author, "4113165000", "post" + index, "PUBLISHED", now.plusSeconds(index), null);
        }
        var page = postService.feed(viewer, board, null, 20);
        assertThat(page.items()).hasSize(20);
        assertThat(page.page().hasNext()).isTrue();
        assertThat(page.page().nextCursor()).isNotBlank();
    }

    @Test
    void boardDeletionRetainsEmptyBoardAndDeletedPostHistory() {
        long emptyBoard = createBoard();
        boardDeletionService.delete(emptyBoard);
        assertThat(jdbc.queryForObject(
                "select count(*) from boards where id = ?", Long.class, emptyBoard
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "select deleted_at is not null from boards where id = ?", Boolean.class, emptyBoard
        )).isTrue();

        long boardWithDeletedPost = createBoard();
        Author author = createAuthor("deletedHistory", "4113165000");
        jdbc.update("update users set active_pet_id = ? where id = ?", author.petId(), author.userId());
        long postId = postService.create(
                author.userId(),
                boardWithDeletedPost,
                new BoardPostCreateRequest("title", "content")
        ).postId();
        postService.delete(author.userId(), postId);

        boardDeletionService.delete(boardWithDeletedPost);

        assertThat(jdbc.queryForObject(
                "select count(*) from boards where id = ?", Long.class, boardWithDeletedPost
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "select deleted_at is not null from boards where id = ?", Boolean.class, boardWithDeletedPost
        )).isTrue();
        assertThat(jdbc.queryForObject(
                "select status from board_posts where id = ?", String.class, postId
        )).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject(
                "select deleted_at is not null from board_posts where id = ?", Boolean.class, postId
        )).isTrue();
    }

    private void assertBusiness(ThrowingSupplier action, String code) {
        assertThatThrownBy(action::get).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode().name()).isEqualTo(code);
    }

    @Test
    void nativeFeedAppliesBoardRegionPublishedBilateralBlockCursorAndNullableTimestamp() {
        long board = createBoard();
        long otherBoard = createBoard();
        long viewer = createUser("viewer", "4113165000");
        Author visible = createAuthor("visible", "4113165000");
        Author tieLow = createAuthor("low", "4113165000");
        Author tieHigh = createAuthor("high", "4113165000");
        Author viewerBlocked = createAuthor("blocked", "4113165000");
        Author authorBlockedViewer = createAuthor("blocksViewer", "4113165000");
        Author otherRegion = createAuthor("otherRegion", "4113351000");
        Instant tie = Instant.parse("2026-08-10T00:00:00Z");

        long lowId = insertPost(board, tieLow, "4113165000", "low", "PUBLISHED", tie, null);
        long highId = insertPost(board, tieHigh, "4113165000", "high", "PUBLISHED", tie, null);
        long visibleId = insertPost(board, visible, "4113165000", "visible", "PUBLISHED", tie.plusSeconds(1), null);
        insertPost(otherBoard, visible, "4113165000", "other board", "PUBLISHED", tie.plusSeconds(10), null);
        insertPost(board, viewerBlocked, "4113165000", "blocked", "PUBLISHED", tie.plusSeconds(2), null);
        insertPost(board, authorBlockedViewer, "4113165000", "blocks viewer", "PUBLISHED", tie.plusSeconds(3), null);
        insertPost(board, otherRegion, "4113351000", "other", "PUBLISHED", tie.plusSeconds(4), null);
        insertPost(board, visible, "4113165000", "deleted", "DELETED", tie.plusSeconds(5), tie.plusSeconds(5));
        jdbc.update("insert into user_blocks (blocker_user_id, blocked_user_id) values (?, ?)", viewer, viewerBlocked.userId());
        jdbc.update("insert into user_blocks (blocker_user_id, blocked_user_id) values (?, ?)", authorBlockedViewer.userId(), viewer);

        List<BoardPost> first = posts.findVisibleFeed(board, "4113165000", viewer, null, null, 10);
        assertThat(first).extracting(BoardPost::getId).containsExactly(visibleId, highId, lowId);

        List<BoardPost> afterTieHigh = posts.findVisibleFeed(board, "4113165000", viewer, tie, highId, 10);
        assertThat(afterTieHigh).extracting(BoardPost::getId).containsExactly(lowId);
    }

    @Test
    void concurrentManagedUpdatesProduceOneActualOptimisticVersionConflict() throws Exception {
        long board = createBoard();
        Author author = createAuthor("version", "4113165000");
        BoardPost created = posts.saveAndFlush(BoardPost.publish(
                board, author.userId(), author.petId(), "4113165000", "original", "content"
        ));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch bothRead = new CountDownLatch(2);
        CountDownLatch allowFlush = new CountDownLatch(1);
        List<Boolean> outcomes = runConcurrently(index -> {
            try {
                tx.executeWithoutResult(status -> {
                    BoardPost post = posts.findById(created.getId()).orElseThrow();
                    bothRead.countDown();
                    await(allowFlush);
                    post.change(index == 0 ? "first" : "second", "content");
                    posts.flush();
                });
                return true;
            } catch (ObjectOptimisticLockingFailureException exception) {
                return false;
            }
        }, bothRead, allowFlush);
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(posts.findById(created.getId()).orElseThrow().getVersion()).isEqualTo(1L);
    }

    /**
     * Proves that the production attachment-domain operation dirties the parent before any child
     * mutation and therefore claims the normal optimistic @Version row.
     */
    @Test
    void attachmentTouchFlushesAParentVersionBeforeChildWorkAndMakesOneConcurrentWinner() throws Exception {
        long postId = persistedPost("attachment-touch-race");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch bothRead = new CountDownLatch(2);
        CountDownLatch allowFlush = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<VersionProbe>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> touchAndFlush(
                        tx, postId, bothRead, allowFlush
                )));
            }
            assertThat(bothRead.await(10, TimeUnit.SECONDS)).isTrue();
            allowFlush.countDown();

            List<VersionProbe> outcomes = new ArrayList<>();
            for (Future<VersionProbe> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).filteredOn(VersionProbe::committed).singleElement()
                    .satisfies(probe -> {
                        assertThat(probe.managedVersion()).isEqualTo(1L);
                        assertThat(probe.databaseVersionAfterFlush()).isEqualTo(1L);
                        assertThat(probe.responseVersion()).isEqualTo(1L);
                    });
            assertThat(outcomes).filteredOn(VersionProbe::optimisticConflict).hasSize(1);
        } finally {
            allowFlush.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(databaseVersion(postId)).isEqualTo(1L);
    }

    @Test
    void oneParentFlushProducesExactlyOneVersionIncrementForTextTouchAndCombinedChanges() {
        long textOnly = persistedPost("text-only");
        VersionProbe textProbe = mutateAndFlush(textOnly, post ->
                post.change("changed", post.getContent())
        );
        assertSingleIncrement(textProbe, textOnly);

        long touchedOnly = persistedPost("attachment-only");
        VersionProbe touchedProbe = mutateAndFlush(touchedOnly, BoardPost::markAttachmentsChanged);
        assertSingleIncrement(touchedProbe, touchedOnly);

        long combined = persistedPost("text-and-attachment");
        VersionProbe combinedProbe = mutateAndFlush(combined, post -> {
            post.change("changed", post.getContent());
            post.markAttachmentsChanged();
        });
        assertSingleIncrement(combinedProbe, combined);
    }

    @Test
    void noOpWithoutParentTouchLeavesVersionUnchanged() {
        long postId = persistedPost("no-op");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        VersionProbe probe = tx.execute(status -> {
            BoardPost post = posts.findById(postId).orElseThrow();
            assertThat(post.change(post.getTitle(), post.getContent())).isFalse();
            posts.flush();
            long version = post.getVersion();
            return VersionProbe.committed(
                    version,
                    databaseVersion(postId),
                    version
            );
        });
        assertThat(probe).isNotNull();
        assertThat(probe.managedVersion()).isZero();
        assertThat(probe.databaseVersionAfterFlush()).isZero();
        assertThat(probe.responseVersion()).isZero();
        assertThat(databaseVersion(postId)).isZero();
    }

    @Test
    void mediaPatchPreservesSameOrderButReplacesReorderedLinksWithoutUniqueConstraintCollision() {
        long boardId = createBoard();
        Author author = createAuthor("media-patch", "4113165000");
        jdbc.update("update users set active_pet_id = ? where id = ?", author.petId(), author.userId());
        BoardPost post = posts.saveAndFlush(BoardPost.publish(
                boardId, author.userId(), author.petId(), "4113165000", "title", "content"
        ));
        long first = createUploadedImage(author.userId());
        long second = createUploadedImage(author.userId());
        postMedia.saveAllAndFlush(List.of(
                BoardPostMedia.attach(post.getId(), first, 0),
                BoardPostMedia.attach(post.getId(), second, 1)
        ));

        BoardPostResponse noOp = postService.update(author.userId(), post.getId(),
                new BoardPostUpdateRequest(false, null, false, null, true, List.of(first, second), 0L));
        assertThat(noOp.version()).isZero();
        assertThat(databaseVersion(post.getId())).isZero();
        assertThat(postMedia.findByPostIdOrderByDisplayOrderAsc(post.getId()))
                .extracting(BoardPostMedia::getMediaId).containsExactly(first, second);

        BoardPostResponse reordered = postService.update(author.userId(), post.getId(),
                new BoardPostUpdateRequest(false, null, false, null, true, List.of(second, first), 0L));
        assertThat(reordered.version()).isEqualTo(1L);
        assertThat(databaseVersion(post.getId())).isEqualTo(1L);
        List<BoardPostMedia> saved = postMedia.findByPostIdOrderByDisplayOrderAsc(post.getId());
        assertThat(saved).extracting(BoardPostMedia::getMediaId).containsExactly(second, first);
        assertThat(saved).extracting(BoardPostMedia::getDisplayOrder).containsExactly(0, 1);

        BoardPostResponse removed = postService.update(author.userId(), post.getId(),
                new BoardPostUpdateRequest(false, null, false, null, true, List.of(), 1L));
        assertThat(removed.version()).isEqualTo(2L);
        assertThat(databaseVersion(post.getId())).isEqualTo(2L);
        assertThat(postMedia.findByPostIdOrderByDisplayOrderAsc(post.getId())).isEmpty();
    }

    @Test
    void concurrentSameVersionMediaPatchThroughTheActorGuardHasOneSuccessAndOneConflict() throws Exception {
        long boardId = createBoard();
        Author author = createAuthor("guarded-media-race", "4113165000");
        jdbc.update("update users set active_pet_id = ? where id = ?", author.petId(), author.userId());
        BoardPost post = posts.saveAndFlush(BoardPost.publish(
                boardId, author.userId(), author.petId(), "4113165000", "title", "content"
        ));
        long original = createUploadedImage(author.userId());
        long firstReplacement = createUploadedImage(author.userId());
        long secondReplacement = createUploadedImage(author.userId());
        postMedia.saveAndFlush(BoardPostMedia.attach(post.getId(), original, 0));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (long requested : List.of(firstReplacement, secondReplacement)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    try {
                        postService.update(author.userId(), post.getId(), new BoardPostUpdateRequest(
                                false, null, false, null, true, List.of(requested), 0L
                        ));
                        return null;
                    } catch (Throwable error) {
                        return error;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Throwable> outcomes = new ArrayList<>();
            for (Future<Throwable> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).filteredOn(java.util.Objects::isNull).singleElement();
            assertBusinessError(
                    outcomes.stream().filter(java.util.Objects::nonNull).findFirst().orElseThrow(),
                    "CONCURRENT_UPDATE_CONFLICT"
            );
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(databaseVersion(post.getId())).isEqualTo(1L);
        assertThat(postMedia.findByPostIdOrderByDisplayOrderAsc(post.getId()))
                .extracting(BoardPostMedia::getMediaId)
                .containsAnyOf(firstReplacement, secondReplacement)
                .doesNotContain(original);
    }

    @Test
    void rollbackAfterMediaReplacementRestoresTheParentVersionAndOriginalLinksAtomically() {
        long boardId = createBoard();
        Author author = createAuthor("media-rollback", "4113165000");
        jdbc.update("update users set active_pet_id = ? where id = ?", author.petId(), author.userId());
        BoardPost post = posts.saveAndFlush(BoardPost.publish(
                boardId, author.userId(), author.petId(), "4113165000", "original", "content"
        ));
        long original = createUploadedImage(author.userId());
        long replacement = createUploadedImage(author.userId());
        postMedia.saveAndFlush(BoardPostMedia.attach(post.getId(), original, 0));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            BoardPostResponse response = postService.update(author.userId(), post.getId(),
                    new BoardPostUpdateRequest(true, "changed", false, null, true, List.of(replacement), 0L));
            assertThat(response.version()).isEqualTo(1L);
            assertThat(postMedia.findByPostIdOrderByDisplayOrderAsc(post.getId()))
                    .extracting(BoardPostMedia::getMediaId).containsExactly(replacement);
            status.setRollbackOnly();
        });

        assertThat(databaseVersion(post.getId())).isZero();
        assertThat(jdbc.queryForObject("select title from board_posts where id = ?", String.class, post.getId()))
                .isEqualTo("original");
        assertThat(postMedia.findByPostIdOrderByDisplayOrderAsc(post.getId()))
                .extracting(BoardPostMedia::getMediaId).containsExactly(original);
    }

    @Test
    void boardFeedHydratesAllImagesWithOneBatchMediaLookupAndAConstantStatementCount() {
        long onePostBoard = createBoard();
        long manyPostBoard = createBoard();
        long viewer = createUser("n-plus-one-viewer", "4113165000");
        Author author = createAuthor("n-plus-one-author", "4113165000");
        Instant now = Instant.parse("2026-08-24T00:00:00Z");
        long onePost = insertPost(onePostBoard, author, "4113165000", "one", "PUBLISHED", now, null);
        long firstMany = insertPost(manyPostBoard, author, "4113165000", "first", "PUBLISHED", now, null);
        long secondMany = insertPost(manyPostBoard, author, "4113165000", "second", "PUBLISHED", now.plusSeconds(1), null);
        long firstMedia = createUploadedImage(author.userId());
        long secondMedia = createUploadedImage(author.userId());
        long thirdMedia = createUploadedImage(author.userId());
        jdbc.update("insert into board_post_media (post_id, media_id, display_order) values (?, ?, 0)", onePost, firstMedia);
        jdbc.update("insert into board_post_media (post_id, media_id, display_order) values (?, ?, 0)", firstMany, secondMedia);
        jdbc.update("insert into board_post_media (post_id, media_id, display_order) values (?, ?, 0)", secondMany, thirdMedia);

        org.hibernate.stat.Statistics stats = statistics();
        clearInvocations(mediaRepository);
        stats.clear();
        postService.feed(viewer, onePostBoard, null, 100);
        long onePostStatements = stats.getPrepareStatementCount();

        clearInvocations(mediaRepository);
        stats.clear();
        var feed = postService.feed(viewer, manyPostBoard, null, 100);
        long manyPostStatements = stats.getPrepareStatementCount();

        assertThat(feed.items()).hasSize(2);
        then(mediaRepository).should(times(1)).findAllById(any());
        then(mediaRepository).should(never()).findById(anyLong());
        assertThat(manyPostStatements).isEqualTo(onePostStatements);
    }

    @Test
    void petDisplayBatchSignsFetchJoinedProfileAssetsWithoutAnyMediaRepositoryLookup() {
        long owner = createUser("profile-batch-owner", "4113165000");
        long firstPet = jdbc.queryForObject("""
                insert into pets (owner_user_id, public_tag, nickname, status)
                values (?, ?, 'first', 'ACTIVE') returning id
                """, Long.class, owner, publicTag("first", 4));
        long secondPet = jdbc.queryForObject("""
                insert into pets (owner_user_id, public_tag, nickname, status)
                values (?, ?, 'second', 'ACTIVE') returning id
                """, Long.class, owner, publicTag("second", 4));
        long firstAsset = createUploadedImage(owner);
        long secondAsset = createUploadedImage(owner);
        jdbc.update("update pets set profile_asset_id = ? where id = ?", firstAsset, firstPet);
        jdbc.update("update pets set profile_asset_id = ? where id = ?", secondAsset, secondPet);

        clearInvocations(mediaRepository);
        statistics().clear();
        var result = petDisplays.getPetDisplaySummaries(List.of(firstPet, secondPet));

        assertThat(result).hasSize(2);
        assertThat(result.get(firstPet).profileUrl()).isNotBlank();
        assertThat(result.get(secondPet).profileUrl()).isNotBlank();
        then(mediaRepository).shouldHaveNoInteractions();
    }

    @Test
    void twoPessimisticReadBoardLocksAreCompatible() throws Exception {
        long board = createBoard();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch secondLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> tx.executeWithoutResult(status -> {
                boards.findByIdForShare(board).orElseThrow();
                firstLocked.countDown();
                await(release);
            }));
            assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> tx.executeWithoutResult(status -> {
                boards.findByIdForShare(board).orElseThrow();
                secondLocked.countDown();
            }));
            assertThat(secondLocked.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void boardReadLockBlocksWriteWithLockTimeoutAndSubsequentWriteSucceeds() throws Exception {
        long board = createBoard();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch readLocked = new CountDownLatch(1);
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> reader = executor.submit(() -> tx.executeWithoutResult(status -> {
                boards.findByIdForShare(board).orElseThrow();
                readLocked.countDown();
                await(releaseRead);
            }));
            assertThat(readLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> writer = executor.submit(() -> {
                try {
                    tx.executeWithoutResult(status -> {
                        jdbc.execute("set local lock_timeout = '250ms'");
                        writeStarted.countDown();
                        boards.findByIdForUpdate(board).orElseThrow();
                    });
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });
            assertThat(writeStarted.await(10, TimeUnit.SECONDS)).isTrue();
            Throwable timeout = writer.get(10, TimeUnit.SECONDS);
            assertThat(timeout).isNotNull();
            assertThat(hasSqlState(timeout, "55P03")).isTrue();
            releaseRead.countDown();
            reader.get(20, TimeUnit.SECONDS);
            tx.executeWithoutResult(status -> boards.findByIdForUpdate(board).orElseThrow());
        } finally {
            releaseRead.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void createFirstThenBoardDeleteKeepsActiveBoardAndPublishedPost() throws Exception {
        long board = createBoard();
        Author author = createAuthor("raceCreate", "4113165000");
        jdbc.update("update users set active_pet_id = ? where id = ?", author.petId(), author.userId());
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch publishedWhileReadLocked = new CountDownLatch(1);
        CountDownLatch deleteStarted = new CountDownLatch(1);
        CountDownLatch allowCreateCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> create = executor.submit(() -> {
                try {
                    tx.executeWithoutResult(status -> {
                        boards.findByIdForShare(board).orElseThrow();
                        postService.create(author.userId(), board,
                                new BoardPostCreateRequest("title", "content"));
                        publishedWhileReadLocked.countDown();
                        await(allowCreateCommit);
                    });
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });
            assertThat(publishedWhileReadLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> delete = executor.submit(() -> {
                try {
                    deleteStarted.countDown();
                    tx.executeWithoutResult(status -> boardDeletionService.delete(board));
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });
            assertThat(deleteStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLockWait();

            allowCreateCommit.countDown();

            assertThat(create.get(30, TimeUnit.SECONDS)).isNull();
            assertBusinessError(delete.get(30, TimeUnit.SECONDS), "BOARD_NOT_EMPTY");
        } finally {
            allowCreateCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject(
                "select deleted_at is null from boards where id = ?", Boolean.class, board
        )).isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from board_posts where board_id = ? and status = 'PUBLISHED'", Long.class, board
        )).isEqualTo(1L);
    }

    @Test
    void deleteFirstThenPostCreateRetainsSoftDeletedBoardWithoutPublishedPost() throws Exception {
        long board = createBoard();
        Author author = createAuthor("raceDelete", "4113165000");
        jdbc.update("update users set active_pet_id = ? where id = ?", author.petId(), author.userId());
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch deleteWriteLocked = new CountDownLatch(1);
        CountDownLatch deletedWhileWriteLocked = new CountDownLatch(1);
        CountDownLatch allowDeleteCommit = new CountDownLatch(1);
        CountDownLatch createStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> delete = executor.submit(() -> {
                try {
                    tx.executeWithoutResult(status -> {
                        boards.findByIdForUpdate(board).orElseThrow();
                        deleteWriteLocked.countDown();
                        boardDeletionService.delete(board);
                        deletedWhileWriteLocked.countDown();
                        await(allowDeleteCommit);
                    });
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });
            assertThat(deleteWriteLocked.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(deletedWhileWriteLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> create = executor.submit(() -> {
                try {
                    createStarted.countDown();
                    postService.create(author.userId(), board,
                            new BoardPostCreateRequest("title", "content"));
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });
            assertThat(createStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLockWait();
            allowDeleteCommit.countDown();

            assertThat(delete.get(30, TimeUnit.SECONDS)).isNull();
            assertBusinessError(create.get(30, TimeUnit.SECONDS), "BOARD_NOT_FOUND");
        } finally {
            allowDeleteCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject(
                "select deleted_at is not null from boards where id = ?", Boolean.class, board
        )).isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from board_posts where board_id = ? and status = 'PUBLISHED'", Long.class, board
        )).isZero();
    }

    @Test
    void activePetSwitchRaceAllowsOnlyMutationSuccessOrAuthorPetForbiddenStateTuples() throws Exception {
        long board = createBoard();
        Author author = createAuthor("raceActor", "4113165000");
        long secondPet = jdbc.queryForObject("""
                insert into pets (owner_user_id, public_tag, nickname, status)
                values (?, ?, 'second', 'ACTIVE') returning id
                """, Long.class, author.userId(), publicTag("second", 4));
        jdbc.update("update users set active_pet_id = ? where id = ?", author.petId(), author.userId());
        BoardPost patchPost = posts.saveAndFlush(BoardPost.publish(board, author.userId(), author.petId(), "4113165000", "title", "content"));
        List<Throwable> patchOutcomes = runStartedConcurrently(
                () -> postService.update(author.userId(), patchPost.getId(), new BoardPostUpdateRequest(true, "changed", false, null, 0)),
                () -> activePetSelectionService.selectActivePet(author.userId(), secondPet)
        );
        assertThat(patchOutcomes.get(1)).isNull();
        BoardPost afterPatch = posts.findById(patchPost.getId()).orElseThrow();
        assertThat(afterPatch.getAuthorPetId()).isEqualTo(author.petId());
        assertThat(jdbc.queryForObject("select active_pet_id from users where id = ?", Long.class, author.userId()))
                .isEqualTo(secondPet);
        if (patchOutcomes.get(0) == null) {
            assertThat(afterPatch.getTitle()).isEqualTo("changed");
            assertThat(afterPatch.getVersion()).isEqualTo(1L);
        } else {
            assertBusinessError(patchOutcomes.get(0), "BOARD_POST_FORBIDDEN");
            assertThat(afterPatch.getTitle()).isEqualTo("title");
            assertThat(afterPatch.getVersion()).isZero();
        }

        jdbc.update("update users set active_pet_id = ? where id = ?", author.petId(), author.userId());
        BoardPost deletePost = posts.saveAndFlush(BoardPost.publish(board, author.userId(), author.petId(), "4113165000", "delete", "content"));
        List<Throwable> deleteOutcomes = runStartedConcurrently(
                () -> postService.delete(author.userId(), deletePost.getId()),
                () -> activePetSelectionService.selectActivePet(author.userId(), secondPet)
        );
        assertThat(deleteOutcomes.get(1)).isNull();
        BoardPost afterDelete = posts.findById(deletePost.getId()).orElseThrow();
        assertThat(afterDelete.getAuthorPetId()).isEqualTo(author.petId());
        assertThat(jdbc.queryForObject("select active_pet_id from users where id = ?", Long.class, author.userId()))
                .isEqualTo(secondPet);
        if (deleteOutcomes.get(0) == null) {
            assertThat(afterDelete.getStatus().name()).isEqualTo("DELETED");
            assertThat(afterDelete.getDeletedAt()).isNotNull();
            assertThat(afterDelete.getVersion()).isEqualTo(1L);
        } else {
            assertBusinessError(deleteOutcomes.get(0), "BOARD_POST_FORBIDDEN");
            assertThat(afterDelete.getStatus().name()).isEqualTo("PUBLISHED");
            assertThat(afterDelete.getDeletedAt()).isNull();
            assertThat(afterDelete.getVersion()).isZero();
        }
    }

    private void assertBusinessError(Throwable error, String code) {
        assertThat(error).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) error).getErrorCode().name()).isEqualTo(code);
    }

    private boolean hasSqlState(Throwable error, String sqlState) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sqlException
                    && sqlState.equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private void awaitDatabaseLockWait() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc.queryForObject("""
                    select exists (
                        select 1
                          from pg_stat_activity
                         where datname = current_database()
                           and wait_event_type = 'Lock'
                    )
                    """, Boolean.class);
            if (Boolean.TRUE.equals(waiting)) {
                return;
            }
        }
        throw new AssertionError("contender did not block on a PostgreSQL lock");
    }

    private List<Throwable> runStartedConcurrently(ThrowingAction first, ThrowingAction second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Throwable>> futures = List.of(executor.submit(() -> runAction(first, ready, start)), executor.submit(() -> runAction(second, ready, start)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Throwable> outcomes = new ArrayList<>();
            for (Future<Throwable> future : futures) outcomes.add(future.get(30, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Throwable runAction(ThrowingAction action, CountDownLatch ready, CountDownLatch start) {
        try { ready.countDown(); await(start); action.run(); return null; }
        catch (Throwable error) { return error; }
    }

    private long createBoard() {
        return jdbc.queryForObject("insert into boards (name) values (?) returning id", Long.class, unique("board"));
    }

    private long persistedPost(String title) {
        long boardId = createBoard();
        Author author = createAuthor(title, "4113165000");
        return posts.saveAndFlush(BoardPost.publish(
                boardId, author.userId(), author.petId(), "4113165000", title, "content"
        )).getId();
    }

    private long createUploadedImage(long userId) {
        return jdbc.queryForObject("""
                insert into media (media_type, path, status, user_id, file_size)
                values ('IMAGE', ?, 'UPLOADED', ?, 1024)
                returning id
                """, Long.class, "board-post/" + UUID.randomUUID(), userId);
    }

    private VersionProbe touchAndFlush(
            TransactionTemplate tx,
            long postId,
            CountDownLatch bothRead,
            CountDownLatch allowFlush
    ) {
        try {
            return tx.execute(status -> {
                BoardPost post = posts.findById(postId).orElseThrow();
                bothRead.countDown();
                await(allowFlush);
                post.markAttachmentsChanged();
                posts.flush();
                long managedVersion = post.getVersion();
                long databaseVersionAfterFlush = databaseVersion(postId);
                // The update response is assembled from this managed entity after the flush.
                long responseVersion = post.getVersion();
                return VersionProbe.committed(
                        managedVersion, databaseVersionAfterFlush, responseVersion
                );
            });
        } catch (ObjectOptimisticLockingFailureException exception) {
            return VersionProbe.conflicted();
        }
    }

    private VersionProbe mutateAndFlush(
            long postId,
            java.util.function.Consumer<BoardPost> mutation
    ) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> {
            BoardPost post = posts.findById(postId).orElseThrow();
            mutation.accept(post);
            posts.flush();
            long managedVersion = post.getVersion();
            long databaseVersionAfterFlush = databaseVersion(postId);
            long responseVersion = post.getVersion();
            return VersionProbe.committed(
                    managedVersion, databaseVersionAfterFlush, responseVersion
            );
        });
    }

    private long databaseVersion(long postId) {
        return jdbc.queryForObject(
                "select version from board_posts where id = ?", Long.class, postId
        );
    }

    private org.hibernate.stat.Statistics statistics() {
        return entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
    }

    private void assertSingleIncrement(VersionProbe probe, long postId) {
        assertThat(probe).isNotNull();
        assertThat(probe.committed()).isTrue();
        assertThat(probe.managedVersion()).isEqualTo(1L);
        assertThat(probe.databaseVersionAfterFlush()).isEqualTo(1L);
        assertThat(probe.responseVersion()).isEqualTo(1L);
        assertThat(databaseVersion(postId)).isEqualTo(1L);
    }

    private Author createAuthor(String nickname, String neighborhood) {
        long userId = createUser(nickname, neighborhood);
        long petId = jdbc.queryForObject("""
                insert into pets (owner_user_id, public_tag, nickname, status)
                values (?, ?, ?, 'ACTIVE') returning id
                """, Long.class, userId, publicTag(nickname, 4), nickname);
        return new Author(userId, petId);
    }

    private long createUser(String nickname, String neighborhood) {
        return jdbc.queryForObject("""
                insert into users (email, password_hash, nickname, public_tag, role, account_status, neighborhood_code)
                values (?, 'encoded', ?, ?, 'USER', 'ACTIVE', ?) returning id
                """, Long.class, unique("user") + "@example.com", nickname, publicTag(nickname, 8), neighborhood);
    }

    private long insertPost(long boardId, Author author, String neighborhood, String title, String status, Instant createdAt, Instant deletedAt) {
        return jdbc.queryForObject("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status, created_at, updated_at, deleted_at)
                values (?, ?, ?, ?, ?, 'content', ?, ?, ?, ?) returning id
                """, Long.class, boardId, author.userId(), author.petId(), neighborhood, title, status,
                java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(createdAt),
                deletedAt == null ? null : java.sql.Timestamp.from(deletedAt));
    }

    private List<Boolean> runConcurrently(IndexedAction action, CountDownLatch bothRead, CountDownLatch allowFlush) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                int worker = index;
                futures.add(executor.submit(() -> action.apply(worker)));
            }
            assertThat(bothRead.await(10, TimeUnit.SECONDS)).isTrue();
            allowFlush.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        } finally {
            allowFlush.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("concurrent test timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private record Author(long userId, long petId) {}

    private record VersionProbe(
            boolean committed,
            boolean optimisticConflict,
            long managedVersion,
            long databaseVersionAfterFlush,
            long responseVersion
    ) {

        private static VersionProbe committed(
                long managedVersion,
                long databaseVersionAfterFlush,
                long responseVersion
        ) {
            return new VersionProbe(
                    true,
                    false,
                    managedVersion,
                    databaseVersionAfterFlush,
                    responseVersion
            );
        }

        private static VersionProbe conflicted() {
            return new VersionProbe(false, true, -1L, -1L, -1L);
        }
    }

    @FunctionalInterface
    private interface IndexedAction { boolean apply(int index) throws Exception; }

    @FunctionalInterface
    private interface ThrowingAction { void run() throws Exception; }

    @FunctionalInterface
    private interface ThrowingSupplier { Object get() throws Exception; }

    private String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
    }

    private String publicTag(String nickname, int suffixLength) {
        return nickname + "#" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, suffixLength).toUpperCase();
    }
}
