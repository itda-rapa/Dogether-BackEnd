package itda.boardpost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.boardpost.domain.BoardPost;
import itda.boardpost.dto.BoardPostCreateRequest;
import itda.boardpost.dto.BoardPostResponse;
import itda.boardpost.dto.BoardPostUpdateRequest;
import itda.boardpost.repository.BoardPostRepository;
import itda.boardpost.service.BoardPostService;
import itda.board.repository.BoardRepository;
import itda.board.service.BoardDeletionService;
import itda.common.exception.BusinessException;
import itda.pet.service.ActivePetSelectionService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class BoardPostPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private BoardPostRepository posts;
    @Autowired private BoardRepository boards;
    @Autowired private BoardPostService postService;
    @Autowired private BoardDeletionService boardDeletionService;
    @Autowired private ActivePetSelectionService activePetSelectionService;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void flywayCreatesBoardPostConstraintsAndRequiredIndexes() {
        long boardId = jdbc.queryForObject("insert into boards (name) values (?) returning id", Long.class, unique("board"));
        long userId = jdbc.queryForObject("""
                insert into users (email, password_hash, nickname, public_tag, role, account_status, neighborhood_code)
                values (?, 'encoded', '작성자', ?, 'USER', 'ACTIVE', '4113111500') returning id
                """, Long.class, unique("user") + "@example.com", publicTag("작성자", 8));
        long petId = jdbc.queryForObject("""
                insert into pets (owner_user_id, public_tag, nickname, status)
                values (?, ?, '반려견', 'ACTIVE') returning id
                """, Long.class, userId, publicTag("반려견", 4));

        assertThatThrownBy(() -> jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113111500', ' ', '내용', 'PUBLISHED')
                """, boardId, userId, petId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status, deleted_at)
                values (?, ?, ?, '4113111500', '제목', '내용', 'PUBLISHED', now())
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
        Author author = createAuthor("constraints", "4113111500");
        Object[] base = {board, author.userId(), author.petId()};
        assertThatThrownBy(() -> jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113111500', 'title', 'content', 'PUBLISHED')
                """, 999999999L, author.userId(), author.petId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113111500', 'title', 'content', 'PUBLISHED')
                """, board, 999999999L, author.petId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113111500', 'title', 'content', 'PUBLISHED')
                """, board, author.userId(), 999999999L)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, 'INVALID', 'title', 'content', 'PUBLISHED')
                """, base)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113111500', 'title', ' ', 'PUBLISHED')
                """, base)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113111500', 'title', ?, 'PUBLISHED')
                """, board, author.userId(), author.petId(), "가".repeat(5001))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113111500', 'title', 'content', 'DRAFT')
                """, base)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113111500', 'title', 'content', 'DELETED')
                """, base)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113111500', 'title', ?, 'PUBLISHED')
                """, board, author.userId(), author.petId(), "가".repeat(5000));
    }

    @Test
    void postServiceAcceptsMaximumUnicodeCodePointTitleAndContent() {
        long board = createBoard();
        Author author = createAuthor("unicode", "4113111500");
        jdbc.update("update users set active_pet_id = ? where id = ?", author.petId(), author.userId());
        BoardPostResponse result = postService.create(author.userId(), board,
                new BoardPostCreateRequest("😀".repeat(120), "가".repeat(5000)));
        assertThat(result.title().codePointCount(0, result.title().length())).isEqualTo(120);
        assertThat(result.content()).hasSize(5000);
    }

    @Test
    void feedReturnsEmptyPageAndValidatesBoardCursorSizeAndLimitPlusOne() {
        long board = createBoard();
        long viewer = createUser("feedViewer", "4113111500");
        assertThat(postService.feed(viewer, board, null, null).items()).isEmpty();
        assertThat(postService.feed(viewer, board, null, null).page().hasNext()).isFalse();
        assertBusiness(() -> postService.feed(viewer, 999999999L, null, null), "BOARD_NOT_FOUND");
        assertBusiness(() -> postService.feed(viewer, board, "malformed", null), "VALIDATION_FAILED");
        assertBusiness(() -> postService.feed(viewer, board, null, 0), "VALIDATION_FAILED");
        assertBusiness(() -> postService.feed(viewer, board, null, 101), "VALIDATION_FAILED");

        Author author = createAuthor("feedAuthor", "4113111500");
        Instant now = Instant.parse("2026-08-10T01:00:00Z");
        for (int index = 0; index < 21; index++) {
            insertPost(board, author, "4113111500", "post" + index, "PUBLISHED", now.plusSeconds(index), null);
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
        Author author = createAuthor("deletedHistory", "4113111500");
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
        long viewer = createUser("viewer", "4113111500");
        Author visible = createAuthor("visible", "4113111500");
        Author tieLow = createAuthor("low", "4113111500");
        Author tieHigh = createAuthor("high", "4113111500");
        Author viewerBlocked = createAuthor("blocked", "4113111500");
        Author authorBlockedViewer = createAuthor("blocksViewer", "4113111500");
        Author otherRegion = createAuthor("otherRegion", "4113111600");
        Instant tie = Instant.parse("2026-08-10T00:00:00Z");

        long lowId = insertPost(board, tieLow, "4113111500", "low", "PUBLISHED", tie, null);
        long highId = insertPost(board, tieHigh, "4113111500", "high", "PUBLISHED", tie, null);
        long visibleId = insertPost(board, visible, "4113111500", "visible", "PUBLISHED", tie.plusSeconds(1), null);
        insertPost(otherBoard, visible, "4113111500", "other board", "PUBLISHED", tie.plusSeconds(10), null);
        insertPost(board, viewerBlocked, "4113111500", "blocked", "PUBLISHED", tie.plusSeconds(2), null);
        insertPost(board, authorBlockedViewer, "4113111500", "blocks viewer", "PUBLISHED", tie.plusSeconds(3), null);
        insertPost(board, otherRegion, "4113111600", "other", "PUBLISHED", tie.plusSeconds(4), null);
        insertPost(board, visible, "4113111500", "deleted", "DELETED", tie.plusSeconds(5), tie.plusSeconds(5));
        jdbc.update("insert into user_blocks (blocker_user_id, blocked_user_id) values (?, ?)", viewer, viewerBlocked.userId());
        jdbc.update("insert into user_blocks (blocker_user_id, blocked_user_id) values (?, ?)", authorBlockedViewer.userId(), viewer);

        List<BoardPost> first = posts.findVisibleFeed(board, "4113111500", viewer, null, null, 10);
        assertThat(first).extracting(BoardPost::getId).containsExactly(visibleId, highId, lowId);

        List<BoardPost> afterTieHigh = posts.findVisibleFeed(board, "4113111500", viewer, tie, highId, 10);
        assertThat(afterTieHigh).extracting(BoardPost::getId).containsExactly(lowId);
    }

    @Test
    void concurrentManagedUpdatesProduceOneActualOptimisticVersionConflict() throws Exception {
        long board = createBoard();
        Author author = createAuthor("version", "4113111500");
        BoardPost created = posts.saveAndFlush(BoardPost.publish(
                board, author.userId(), author.petId(), "4113111500", "original", "content"
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
        Author author = createAuthor("raceCreate", "4113111500");
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
        Author author = createAuthor("raceDelete", "4113111500");
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
        Author author = createAuthor("raceActor", "4113111500");
        long secondPet = jdbc.queryForObject("""
                insert into pets (owner_user_id, public_tag, nickname, status)
                values (?, ?, 'second', 'ACTIVE') returning id
                """, Long.class, author.userId(), publicTag("second", 4));
        jdbc.update("update users set active_pet_id = ? where id = ?", author.petId(), author.userId());
        BoardPost patchPost = posts.saveAndFlush(BoardPost.publish(board, author.userId(), author.petId(), "4113111500", "title", "content"));
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
        BoardPost deletePost = posts.saveAndFlush(BoardPost.publish(board, author.userId(), author.petId(), "4113111500", "delete", "content"));
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
