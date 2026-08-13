package itda.setlog.service;

import static org.assertj.core.api.Assertions.assertThat;

import itda.setlog.domain.ReactionType;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration"
})
class SetlogReactionPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SetlogReactionService reactionService;

    @BeforeEach
    void createNeighborhood() {
        jdbcTemplate.update("""
                insert into neighborhoods (
                    code, sido_name, sigungu_name, eupmyeondong_name
                ) values ('4113111500', '경기도', '성남시', '수내동')
                on conflict (code) do nothing
                """);
    }

    @Test
    void duplicateCommandsAreIdempotentAndCountersMatchRowsForUserSetlog() {
        Actor author = createActor();
        Actor reactor = createActor();
        Long setlogId = createUserSetlog(author);

        reactionService.addReaction(reactor.userId(), setlogId, ReactionType.CUTE);
        reactionService.addReaction(reactor.userId(), setlogId, ReactionType.CUTE);

        assertThat(reactionRows(setlogId, "CUTE")).isEqualTo(1L);
        assertThat(counter(setlogId, "reaction_cute_count")).isEqualTo(1);

        reactionService.removeReaction(reactor.userId(), setlogId, ReactionType.CUTE);
        reactionService.removeReaction(reactor.userId(), setlogId, ReactionType.CUTE);

        assertThat(reactionRows(setlogId, "CUTE")).isZero();
        assertThat(counter(setlogId, "reaction_cute_count")).isZero();
    }

    @Test
    void concurrentDifferentPetsKeepEveryReactionAndExactCounter() throws Exception {
        Actor author = createActor();
        Actor first = createActor();
        Actor second = createActor();
        Long setlogId = createUserSetlog(author);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Void>> futures = List.of(
                    submitReaction(executor, ready, start, first, setlogId),
                    submitReaction(executor, ready, start, second, setlogId)
            );
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }

            assertThat(reactionRows(setlogId, "LIKE")).isEqualTo(2L);
            assertThat(counter(setlogId, "reaction_like_count")).isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Future<Void> submitReaction(
            ExecutorService executor,
            CountDownLatch ready,
            CountDownLatch start,
            Actor actor,
            Long setlogId
    ) {
        return executor.submit(() -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("reaction start timed out");
            }
            reactionService.addReaction(actor.userId(), setlogId, ReactionType.LIKE);
            return null;
        });
    }

    private Actor createActor() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        Long userId = jdbcTemplate.queryForObject("""
                insert into users (
                    email, password_hash, nickname, public_tag,
                    role, account_status, neighborhood_code
                ) values (?, 'encoded', '보호자', ?, 'USER', 'ACTIVE', '4113111500')
                returning id
                """, Long.class, unique + "@example.com",
                "보호자#" + unique.substring(0, 8));
        Long petId = jdbcTemplate.queryForObject("""
                insert into pets (owner_user_id, public_tag, nickname, status)
                values (?, ?, '반려견', 'ACTIVE') returning id
                """, Long.class, userId,
                "반려견#" + unique.substring(0, 4).toUpperCase());
        jdbcTemplate.update(
                "update users set active_pet_id = ? where id = ?",
                petId, userId
        );
        return new Actor(userId, petId);
    }

    private Long createUserSetlog(Actor author) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        Long mediaId = jdbcTemplate.queryForObject("""
                insert into media (
                    media_type, path, status, user_id, file_size
                ) values ('VIDEO', ?, 'UPLOADED', ?, 1024) returning id
                """, Long.class, "setlogs/" + unique + ".mp4", author.userId());
        return jdbcTemplate.queryForObject("""
                insert into setlogs (
                    author_pet_id, media_id, caption, status, is_seed
                ) values (?, ?, '같이 놀아요', 'VISIBLE', false) returning id
                """, Long.class, author.petId(), mediaId);
    }

    private long reactionRows(Long setlogId, String type) {
        return jdbcTemplate.queryForObject("""
                select count(*) from setlog_reactions
                 where setlog_id = ? and type = ?
                """, Long.class, setlogId, type);
    }

    private int counter(Long setlogId, String column) {
        return jdbcTemplate.queryForObject(
                "select " + column + " from setlogs where id = ?",
                Integer.class,
                setlogId
        );
    }

    private record Actor(Long userId, Long petId) {
    }
}
