package itda.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import itda.chat.dto.EnsureDirectRoomResult;
import itda.comment.service.BoardCommentDirectRoomService;
import itda.common.security.CurrentUser;
import itda.user.domain.Role;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class BoardCommentDirectRoomPostgreSqlIntegrationTest {

    private static final int WORKERS = 8;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BoardCommentDirectRoomService directRooms;
    @Autowired private PlatformTransactionManager transactionManager;

    private ExecutorService executor;
    private long authorId;
    private long authorPetId;
    private long commenterId;
    private long commenterPetId;
    private long postId;
    private long commentId;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate chat_messages, chat_room_participants, chat_rooms restart identity cascade");
        jdbc.execute("truncate board_post_comments, board_posts, boards, pets, users restart identity cascade");

        executor = Executors.newFixedThreadPool(2);

        authorId = createUser("author");
        authorPetId = createPet(authorId, "author-pet");
        activate(authorId, authorPetId);
        commenterId = createUser("commenter");
        commenterPetId = createPet(commenterId, "commenter-pet");
        activate(commenterId, commenterPetId);
        long boardId = jdbc.queryForObject(
                "insert into boards (name) values (?) returning id",
                Long.class,
                "board-" + UUID.randomUUID().toString().substring(0, 8)
        );
        postId = jdbc.queryForObject("""
                insert into board_posts
                    (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status, version)
                values (?, ?, ?, '4113111500', 'title', 'content', 'PUBLISHED', 0)
                returning id
                """, Long.class, boardId, authorId, authorPetId);
        commentId = jdbc.queryForObject("""
                insert into board_post_comments
                    (post_id, author_user_id, author_pet_id, content, version)
                values (?, ?, ?, 'answer', 0)
                returning id
                """, Long.class, postId, commenterId, commenterPetId);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void endpointCreatesAndReusesOneRoomForEitherTargetPet() throws Exception {
        String first = mockMvc.perform(post(
                        "/posts/{postId}/comments/{commentId}/direct-room", postId, commentId
                ).with(user(principal(authorId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isNew").value(true))
                .andReturn().getResponse().getContentAsString();
        long roomId = ((Number) JsonPath.read(first, "$.data.roomId")).longValue();

        mockMvc.perform(post(
                        "/posts/{postId}/comments/{commentId}/direct-room", postId, commentId
                ).with(user(principal(commenterId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(roomId))
                .andExpect(jsonPath("$.data.isNew").value(false));

        assertThat(jdbc.queryForObject("select count(*) from chat_rooms", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from chat_room_participants", Long.class)).isEqualTo(2L);
    }

    @Test
    void suspendedCommentAuthorPetIsHiddenFromDirectRoom() throws Exception {
        jdbc.update("update pets set status = 'SUSPENDED' where id = ?", commenterPetId);

        mockMvc.perform(post(
                        "/posts/{postId}/comments/{commentId}/direct-room", postId, commentId
                ).with(user(principal(authorId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHAT_ROOM_NOT_FOUND"));

        assertNoDirectRoom();
    }

    @Test
    void deletedCommentAuthorPetIsHiddenFromDirectRoom() throws Exception {
        jdbc.update("""
                update pets
                set status = 'DELETED', deleted_at = current_timestamp
                where id = ?
                """, commenterPetId);

        mockMvc.perform(post(
                        "/posts/{postId}/comments/{commentId}/direct-room", postId, commentId
                ).with(user(principal(authorId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHAT_ROOM_NOT_FOUND"));

        assertNoDirectRoom();
    }

    @Test
    void inactiveCommentAuthorUserIsHiddenFromDirectRoom() throws Exception {
        jdbc.update("update users set account_status = 'SUSPENDED' where id = ?", commenterId);

        mockMvc.perform(post(
                        "/posts/{postId}/comments/{commentId}/direct-room", postId, commentId
                ).with(user(principal(authorId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHAT_ROOM_NOT_FOUND"));

        assertNoDirectRoom();
    }

    @Test
    void suspendedPostAuthorPetIsHiddenFromDirectRoom() throws Exception {
        jdbc.update("update pets set status = 'SUSPENDED' where id = ?", authorPetId);

        mockMvc.perform(post(
                        "/posts/{postId}/comments/{commentId}/direct-room", postId, commentId
                ).with(user(principal(commenterId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHAT_ROOM_NOT_FOUND"));

        assertNoDirectRoom();
    }

    @Test
    void deletedPostAuthorPetIsHiddenFromDirectRoom() throws Exception {
        jdbc.update("""
                update pets
                set status = 'DELETED', deleted_at = current_timestamp
                where id = ?
                """, authorPetId);

        mockMvc.perform(post(
                        "/posts/{postId}/comments/{commentId}/direct-room", postId, commentId
                ).with(user(principal(commenterId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHAT_ROOM_NOT_FOUND"));

        assertNoDirectRoom();
    }

    @Test
    void inactivePostAuthorUserIsHiddenFromDirectRoom() throws Exception {
        jdbc.update("update users set account_status = 'SUSPENDED' where id = ?", authorId);

        mockMvc.perform(post(
                        "/posts/{postId}/comments/{commentId}/direct-room", postId, commentId
                ).with(user(principal(commenterId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHAT_ROOM_NOT_FOUND"));

        assertNoDirectRoom();
    }

    @Test
    void concurrentIdenticalRequestsStillCreateOneDirectRoom() throws Exception {
        List<EnsureDirectRoomResult> results = runConcurrently(
                () -> directRooms.ensureDirectRoom(authorId, postId, commentId)
        );

        assertThat(results).hasSize(WORKERS);
        assertThat(results).extracting(EnsureDirectRoomResult::roomId)
                .containsOnly(results.getFirst().roomId());
        assertThat(results.stream().filter(EnsureDirectRoomResult::isNew).count()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from chat_rooms", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from chat_room_participants", Long.class)).isEqualTo(2L);
    }

    @Test
    void boardToDirectHoldsTheTargetPetLockUntilItsTransactionEnds() throws Exception {
        assertCompetingRowLockWaits(RowLockTarget.PETS, commenterPetId);
    }

    @Test
    void boardToDirectHoldsTheTargetUserLockUntilItsTransactionEnds() throws Exception {
        assertCompetingRowLockWaits(RowLockTarget.USERS, commenterId);
    }

    private void assertCompetingRowLockWaits(RowLockTarget target, Long rowId) throws Exception {
        CountDownLatch pairLocked = new CountDownLatch(1);
        CountDownLatch releasePair = new CountDownLatch(1);

        Future<Void> owner = executor.submit(() -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                directRooms.ensureDirectRoom(authorId, postId, commentId);
                pairLocked.countDown();
                awaitLatch(releasePair);
            });
            return null;
        });

        assertThat(pairLocked.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Long> competitor = executor.submit(() ->
                new TransactionTemplate(transactionManager).execute(status -> {
                    jdbc.execute("SET LOCAL lock_timeout = '500ms'");
                    return lockRow(target, rowId);
                })
        );

        ExecutionException competitorFailure;
        try {
            competitorFailure = assertThrows(
                    ExecutionException.class,
                    () -> competitor.get(10, TimeUnit.SECONDS)
            );
            SQLException postgresFailure = findPostgreSqlException(competitorFailure);
            assertThat(postgresFailure.getSQLState()).isEqualTo("55P03");
        } finally {
            releasePair.countDown();
        }
        owner.get(10, TimeUnit.SECONDS);

        Long lockedAfterRelease = new TransactionTemplate(transactionManager).execute(
                status -> lockRow(target, rowId)
        );
        assertThat(lockedAfterRelease).isEqualTo(rowId);
    }

    private Long lockRow(RowLockTarget target, Long rowId) {
        return jdbc.queryForObject(target.sql(), Long.class, rowId);
    }

    private SQLException findPostgreSqlException(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof SQLException sqlException
                    && cause.getClass().getName()
                            .equals("org.postgresql.util.PSQLException")) {
                return sqlException;
            }
            cause = cause.getCause();
        }
        throw new AssertionError(
                "Expected a PostgreSQL lock timeout in the exception cause chain",
                failure
        );
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while holding interaction pair locks");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding interaction pair locks", exception);
        }
    }

    private <T> List<T> runConcurrently(Callable<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < WORKERS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return action.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertNoDirectRoom() {
        assertThat(jdbc.queryForObject("select count(*) from chat_rooms", Long.class)).isZero();
    }

    private long createUser(String nickname) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return jdbc.queryForObject("""
                insert into users
                    (email, password_hash, nickname, public_tag, role, account_status, neighborhood_code, version)
                values (?, 'encoded', ?, ?, 'USER', 'ACTIVE', '4113111500', 0)
                returning id
                """, Long.class, nickname + suffix + "@test.com", nickname, nickname + "#" + suffix.toUpperCase());
    }

    private long createPet(long ownerId, String nickname) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
        return jdbc.queryForObject("""
                insert into pets
                    (owner_user_id, public_tag, nickname, status, version)
                values (?, ?, ?, 'ACTIVE', 0)
                returning id
                """, Long.class, ownerId, nickname + "#" + suffix, nickname);
    }

    private void activate(long userId, long petId) {
        jdbc.update("update users set active_pet_id = ? where id = ?", petId, userId);
    }

    private CurrentUser principal(long userId) {
        return new CurrentUser(userId, "user" + userId + "@test.com", Role.USER);
    }

    private enum RowLockTarget {
        USERS("update users set account_status = 'SUSPENDED' where id = ? returning id"),
        PETS("update pets set status = 'SUSPENDED' where id = ? returning id");

        private final String sql;

        RowLockTarget(String sql) {
            this.sql = sql;
        }

        String sql() {
            return sql;
        }
    }
}
