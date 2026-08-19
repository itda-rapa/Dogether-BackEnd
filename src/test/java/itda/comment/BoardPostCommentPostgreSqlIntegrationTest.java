package itda.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.boardpost.domain.BoardPost;
import itda.boardpost.domain.PostStatus;
import itda.boardpost.repository.BoardPostRepository;
import itda.boardpost.service.BoardPostService;
import itda.comment.domain.BoardPostComment;
import itda.comment.dto.CommentCreateRequest;
import itda.comment.repository.BoardPostCommentRepository;
import itda.comment.service.BoardPostCommentService;
import itda.common.exception.BusinessException;
import itda.common.security.CurrentUser;
import itda.user.domain.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class BoardPostCommentPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private BoardPostCommentRepository comments;
    @Autowired private BoardPostRepository posts;
    @Autowired private BoardPostCommentService commentService;
    @Autowired private BoardPostService postService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MockMvc mockMvc;

    @Test
    void flywayCreatesForeignKeysChecksAndTheVisiblePartialIndexWithoutCascade() {
        long boardId = createBoard();
        Author author = createAuthor("author", "4113111500");
        long postId = insertPost(boardId, author, "PUBLISHED");

        assertThatThrownBy(() -> insertComment(999999999L, author.userId(), author.petId(), "content", Instant.now(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertComment(postId, 999999999L, author.petId(), "content", Instant.now(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertComment(postId, author.userId(), 999999999L, "content", Instant.now(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertComment(postId, author.userId(), author.petId(), " \t\n", Instant.now(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertComment(postId, author.userId(), author.petId(), "😀".repeat(5001), Instant.now(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertComment(postId, author.userId(), author.petId(), "😀".repeat(5000), Instant.now(), null);

        String index = jdbc.queryForObject("""
                select indexdef from pg_indexes
                 where schemaname = current_schema()
                   and indexname = 'ix_board_post_comments_visible_post_created_id'
                """, String.class);
        assertThat(index).contains("post_id, created_at, id").contains("WHERE (deleted_at IS NULL)");
        List<String> foreignKeyRules = jdbc.queryForList("""
                select delete_rule from information_schema.referential_constraints rc
                join information_schema.table_constraints tc
                  on tc.constraint_catalog = rc.constraint_catalog
                 and tc.constraint_schema = rc.constraint_schema
                 and tc.constraint_name = rc.constraint_name
                where tc.table_schema = current_schema()
                  and tc.table_name = 'board_post_comments'
                  and tc.constraint_name in (
                      'fk_board_post_comments_post',
                      'fk_board_post_comments_author_user',
                      'fk_board_post_comments_author_pet'
                  )
                """, String.class);
        assertThat(foreignKeyRules).containsExactlyInAnyOrder("NO ACTION", "NO ACTION", "NO ACTION");
    }

    @Test
    void softDeletedCommentsAreHiddenButRowsRemainAndPostSoftDeleteRetainsHistory() {
        long boardId = createBoard();
        Author author = createAuthor("author", "4113111500");
        long postId = insertPost(boardId, author, "PUBLISHED");
        long visibleId = insertComment(postId, author.userId(), author.petId(), "visible", Instant.now(), null);
        long deletedId = insertComment(postId, author.userId(), author.petId(), "deleted", Instant.now().plusSeconds(1), Instant.now());

        assertThat(comments.findVisibleByPostId(postId, author.userId(), null, null, 10))
                .extracting(BoardPostComment::getId).containsExactly(visibleId);
        jdbc.update("update board_posts set status = 'DELETED', deleted_at = now() where id = ?", postId);
        assertThat(jdbc.queryForObject("select count(*) from board_post_comments where post_id = ?", Long.class, postId))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("select deleted_at is not null from board_post_comments where id = ?", Boolean.class, deletedId))
                .isTrue();
    }

    @Test
    void nativeListFiltersBlockedCommentsBeforeLimitAndUsesCreatedAtIdKeyset() {
        long boardId = createBoard();
        long viewer = createUser("viewer", "4113111500");
        Author postAuthor = createAuthor("postAuthor", "4113111500");
        Author blocked = createAuthor("blocked", "4113111500");
        Author visible = createAuthor("visible", "4113111500");
        long postId = insertPost(boardId, postAuthor, "PUBLISHED");
        Instant tie = Instant.parse("2026-08-10T00:00:00Z");
        long hiddenId = insertComment(postId, blocked.userId(), blocked.petId(), "hidden", tie, null);
        long firstVisibleId = insertComment(postId, visible.userId(), visible.petId(), "first", tie, null);
        long secondVisibleId = insertComment(postId, postAuthor.userId(), postAuthor.petId(), "second", tie.plusSeconds(1), null);
        jdbc.update("insert into user_blocks (blocker_user_id, blocked_user_id) values (?, ?)", viewer, blocked.userId());

        List<BoardPostComment> first = comments.findVisibleByPostId(postId, viewer, null, null, 2);
        assertThat(first).extracting(BoardPostComment::getId).containsExactly(firstVisibleId, secondVisibleId)
                .doesNotContain(hiddenId);
        assertThat(comments.findVisibleByPostId(postId, viewer, tie, firstVisibleId, 10))
                .extracting(BoardPostComment::getId).containsExactly(secondVisibleId);
    }

    @Test
    void listApiReturnsOldestFirstCursorPageFiltersReverseBlocksAndExcludesSoftDeletedRows() throws Exception {
        long boardId = createBoard();
        Author postAuthor = createAuthor("postAuthor", "4113111500");
        Author viewer = createAuthor("viewer", "4113111500");
        Author reverseBlocker = createAuthor("reverseBlocker", "4113111500");
        long postId = insertPost(boardId, postAuthor, "PUBLISHED");
        Instant created = Instant.parse("2026-08-10T00:00:00Z");
        long firstId = insertComment(postId, postAuthor.userId(), postAuthor.petId(), "first", created, null);
        insertComment(postId, reverseBlocker.userId(), reverseBlocker.petId(), "blocked", created.plusSeconds(1), null);
        long deletedId = insertComment(postId, postAuthor.userId(), postAuthor.petId(), "deleted", created.plusSeconds(2), created.plusSeconds(3));
        long secondId = insertComment(postId, postAuthor.userId(), postAuthor.petId(), "second", created.plusSeconds(4), null);
        jdbc.update("insert into user_blocks (blocker_user_id, blocked_user_id) values (?, ?)", reverseBlocker.userId(), viewer.userId());

        String firstPage = mockMvc.perform(get("/posts/{postId}/comments", postId)
                        .param("size", "1").with(user(principal(viewer.userId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].commentId").value(firstId))
                .andExpect(jsonPath("$.data.items[0].content").value("first"))
                .andExpect(jsonPath("$.data.page.hasNext").value(true))
                .andExpect(jsonPath("$.data.page.nextCursor").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String cursor = com.jayway.jsonpath.JsonPath.read(firstPage, "$.data.page.nextCursor");
        mockMvc.perform(get("/posts/{postId}/comments", postId)
                        .param("size", "1").param("cursor", cursor).with(user(principal(viewer.userId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].commentId").value(secondId))
                .andExpect(jsonPath("$.data.page.hasNext").value(false));
        assertThat(jdbc.queryForObject("select deleted_at is not null from board_post_comments where id = ?", Boolean.class, deletedId))
                .isTrue();
    }

    @Test
    void listApiRejectsInvalidCursorAndSize() throws Exception {
        long boardId = createBoard();
        Author postAuthor = createAuthor("postAuthor", "4113111500");
        Author viewer = createAuthor("viewer", "4113111500");
        long postId = insertPost(boardId, postAuthor, "PUBLISHED");
        for (String size : List.of("0", "101", "-1")) {
            mockMvc.perform(get("/posts/{postId}/comments", postId)
                            .param("size", size).with(user(principal(viewer.userId()))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(get("/posts/{postId}/comments", postId)
                        .param("cursor", "not-a-comment-cursor").with(user(principal(viewer.userId()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void concurrentManagedCommentUpdatesProduceOneActualOptimisticLockFailure() throws Exception {
        long boardId = createBoard();
        Author author = createAuthor("author", "4113111500");
        long postId = insertPost(boardId, author, "PUBLISHED");
        BoardPostComment comment = comments.saveAndFlush(BoardPostComment.create(postId, author.userId(), author.petId(), "original"));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch bothRead = new CountDownLatch(2);
        CountDownLatch flush = new CountDownLatch(1);
        List<Boolean> outcomes = runConcurrently(index -> {
            try {
                tx.executeWithoutResult(status -> {
                    BoardPostComment managed = comments.findById(comment.getId()).orElseThrow();
                    bothRead.countDown();
                    await(flush);
                    managed.changeContent(index == 0 ? "first" : "second");
                    comments.flush();
                });
                return true;
            } catch (ObjectOptimisticLockingFailureException exception) {
                return false;
            }
        }, bothRead, flush);
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(comments.findById(comment.getId()).orElseThrow().getVersion()).isEqualTo(1L);
    }

    @Test
    void softDeleteIsVersionedManagedUpdateAndConflictsWithAStalePatch() throws Exception {
        long boardId = createBoard();
        Author author = createAuthor("author", "4113111500");
        long postId = insertPost(boardId, author, "PUBLISHED");
        BoardPostComment comment = comments.saveAndFlush(BoardPostComment.create(postId, author.userId(), author.petId(), "original"));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch patchRead = new CountDownLatch(1);
        CountDownLatch deleteFlushed = new CountDownLatch(1);
        CountDownLatch allowPatchFlush = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> patch = executor.submit(() -> inTransaction(tx, () -> {
                BoardPostComment stale = comments.findById(comment.getId()).orElseThrow();
                patchRead.countDown();
                await(allowPatchFlush);
                stale.changeContent("patched");
                comments.flush();
            }));
            assertThat(patchRead.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> delete = executor.submit(() -> inTransaction(tx, () -> {
                BoardPostComment managed = comments.findById(comment.getId()).orElseThrow();
                managed.delete(Instant.now());
                comments.flush();
                deleteFlushed.countDown();
            }));
            assertThat(deleteFlushed.await(10, TimeUnit.SECONDS)).isTrue();
            allowPatchFlush.countDown();
            assertThat(delete.get(30, TimeUnit.SECONDS)).isNull();
            assertThat(patch.get(30, TimeUnit.SECONDS)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        } finally {
            allowPatchFlush.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        BoardPostComment persisted = comments.findById(comment.getId()).orElseThrow();
        assertThat(persisted.getDeletedAt()).isNotNull();
        assertThat(persisted.getVersion()).isEqualTo(1L);
        assertThat(persisted.getContent()).isEqualTo("original");
    }

    @Test
    void createFirstThenPostDeleteWaitsForShareLockAndRetainsCommentHistory() throws Exception {
        long boardId = createBoard();
        Author postAuthor = createAuthor("postAuthor", "4113111500");
        Author commenter = createAuthor("commenter", "4113111500");
        activate(postAuthor);
        activate(commenter);
        long postId = insertPost(boardId, postAuthor, "PUBLISHED");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch commentSavedWithReadLock = new CountDownLatch(1);
        CountDownLatch allowCommentCommit = new CountDownLatch(1);
        CountDownLatch deleteStarted = new CountDownLatch(1);
        AtomicLong deleteBackendPid = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> create = executor.submit(() -> inTransaction(tx, () -> {
                commentService.create(commenter.userId(), postId, new CommentCreateRequest("content"));
                commentSavedWithReadLock.countDown();
                await(allowCommentCommit);
            }));
            assertThat(commentSavedWithReadLock.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> delete = executor.submit(() -> inTransaction(tx, () -> {
                deleteBackendPid.set(jdbc.queryForObject("select pg_backend_pid()", Long.class));
                postService.delete(postAuthor.userId(), postId);
                deleteStarted.countDown();
                posts.flush();
            }));
            assertThat(deleteStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLockWait(deleteBackendPid.get());
            allowCommentCommit.countDown();
            assertThat(create.get(30, TimeUnit.SECONDS)).isNull();
            assertThat(delete.get(30, TimeUnit.SECONDS)).isNull();
        } finally {
            allowCommentCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("select status from board_posts where id = ?", String.class, postId)).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("select count(*) from board_post_comments where post_id = ?", Long.class, postId)).isEqualTo(1L);
    }

    @Test
    void deleteFirstWithFlushedWriteLockMakesWaitingCommentCreateReevaluatePublishedPredicate() throws Exception {
        long boardId = createBoard();
        Author postAuthor = createAuthor("postAuthor", "4113111500");
        Author commenter = createAuthor("commenter", "4113111500");
        activate(postAuthor);
        activate(commenter);
        long postId = insertPost(boardId, postAuthor, "PUBLISHED");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch deleteWriteLocked = new CountDownLatch(1);
        CountDownLatch allowDeleteCommit = new CountDownLatch(1);
        CountDownLatch createStarted = new CountDownLatch(1);
        AtomicLong createBackendPid = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> delete = executor.submit(() -> inTransaction(tx, () -> {
                postService.delete(postAuthor.userId(), postId);
                posts.flush();
                deleteWriteLocked.countDown();
                await(allowDeleteCommit);
            }));
            assertThat(deleteWriteLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> create = executor.submit(() -> inTransaction(tx, () -> {
                createBackendPid.set(jdbc.queryForObject("select pg_backend_pid()", Long.class));
                createStarted.countDown();
                    commentService.create(commenter.userId(), postId, new CommentCreateRequest("content"));
            }));
            assertThat(createStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLockWait(createBackendPid.get());
            allowDeleteCommit.countDown();
            assertThat(delete.get(30, TimeUnit.SECONDS)).isNull();
            assertBusiness(create.get(30, TimeUnit.SECONDS), "BOARD_POST_NOT_FOUND");
        } finally {
            allowDeleteCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("select count(*) from board_post_comments where post_id = ?", Long.class, postId)).isZero();
    }

    private long createBoard() {
        return jdbc.queryForObject("insert into boards (name) values (?) returning id", Long.class, unique("board"));
    }

    private Author createAuthor(String nickname, String neighborhood) {
        long userId = createUser(nickname, neighborhood);
        long petId = jdbc.queryForObject("""
                insert into pets (owner_user_id, public_tag, nickname, status)
                values (?, ?, ?, 'ACTIVE') returning id
                """, Long.class, userId, tag(nickname, 4), nickname);
        return new Author(userId, petId);
    }

    private long createUser(String nickname, String neighborhood) {
        return jdbc.queryForObject("""
                insert into users (email, password_hash, nickname, public_tag, role, account_status, neighborhood_code)
                values (?, 'encoded', ?, ?, 'USER', 'ACTIVE', ?) returning id
                """, Long.class, unique("user") + "@example.com", nickname, tag(nickname, 8), neighborhood);
    }

    private long insertPost(long boardId, Author author, String status) {
        return jdbc.queryForObject("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113111500', 'title', 'content', ?) returning id
                """, Long.class, boardId, author.userId(), author.petId(), status);
    }

    private long insertComment(long postId, long userId, long petId, String content, Instant createdAt, Instant deletedAt) {
        return jdbc.queryForObject("""
                insert into board_post_comments (post_id, author_user_id, author_pet_id, content, created_at, updated_at, deleted_at)
                values (?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, postId, userId, petId, content,
                java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(createdAt),
                deletedAt == null ? null : java.sql.Timestamp.from(deletedAt));
    }

    private void activate(Author author) {
        jdbc.update("update users set active_pet_id = ? where id = ?", author.petId(), author.userId());
    }

    private List<Boolean> runConcurrently(IndexedAction action, CountDownLatch bothRead, CountDownLatch flush) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = List.of(executor.submit(() -> action.apply(0)), executor.submit(() -> action.apply(1)));
            assertThat(bothRead.await(10, TimeUnit.SECONDS)).isTrue();
            flush.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        } finally {
            flush.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Throwable inTransaction(TransactionTemplate tx, ThrowingAction action) {
        try {
            tx.executeWithoutResult(status -> {
                try {
                    action.run();
                } catch (Exception exception) {
                    throw new TransactionActionFailure(exception);
                }
            });
            return null;
        } catch (Throwable error) {
            if (error instanceof TransactionActionFailure failure) {
                return failure.getCause();
            }
            return error;
        }
    }

    private void awaitDatabaseLockWait(long contenderBackendPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc.queryForObject("""
                    select wait_event_type = 'Lock' and cardinality(pg_blocking_pids(pid)) > 0
                      from pg_stat_activity where pid = ?
                    """, Boolean.class, contenderBackendPid);
            if (Boolean.TRUE.equals(waiting)) return;
        }
        throw new AssertionError("contender did not block on a PostgreSQL lock");
    }

    private void assertBusiness(Throwable error, String code) {
        assertThat(error).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) error).getErrorCode().name()).isEqualTo(code);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("concurrent test timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
    }

    private String tag(String nickname, int length) {
        return nickname + "#" + UUID.randomUUID().toString().replace("-", "").substring(0, length).toUpperCase();
    }

    private CurrentUser principal(long userId) {
        return new CurrentUser(userId, "user" + userId + "@test.com", Role.USER);
    }

    private record Author(long userId, long petId) {}

    @FunctionalInterface
    private interface IndexedAction { boolean apply(int index) throws Exception; }

    @FunctionalInterface
    private interface ThrowingAction { void run() throws Exception; }

    private static final class TransactionActionFailure extends RuntimeException {
        private TransactionActionFailure(Exception cause) {
            super(cause);
        }
    }
}
