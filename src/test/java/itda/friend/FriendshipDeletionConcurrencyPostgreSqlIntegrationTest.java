package itda.friend;

import static org.assertj.core.api.Assertions.assertThat;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.service.FriendshipDeletionService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class FriendshipDeletionConcurrencyPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer(
                DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                        .asCompatibleSubstituteFor("postgres")
        );

    @Autowired
    private FriendshipDeletionService friendshipDeletionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE chat_messages, chat_room_participants, chat_rooms,
                    greetings, setlog_reactions, setlogs, media, user_blocks,
                    friendships, friend_requests, pets, users
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void concurrentDeletesProduceOneSuccessAndOneNotFound() throws Exception {
        Fixture fixture = committedFixture();
        assertThat(friendshipCount(
                fixture.sourcePetId(),
                fixture.targetPetId()
        )).isOne();

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> first = executor.submit(() -> delete(
                    fixture,
                    readyLatch,
                    startLatch
            ));
            Future<Outcome> second = executor.submit(() -> delete(
                    fixture,
                    readyLatch,
                    startLatch
            ));

            assertThat(readyLatch.await(10, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            List<Outcome> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );

            assertThat(outcomes)
                    .filteredOn(Outcome::success)
                    .hasSize(1);
            assertThat(outcomes)
                    .filteredOn(outcome -> !outcome.success())
                    .singleElement()
                    .satisfies(outcome -> {
                        assertThat(outcome.errorCode())
                                .isEqualTo(ErrorCode.FRIENDSHIP_NOT_FOUND);
                        assertThat(outcome.failure())
                                .isInstanceOf(BusinessException.class);
                    });
            assertThat(friendshipCount(
                    fixture.sourcePetId(),
                    fixture.targetPetId()
            )).isZero();
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                        .isTrue();
            }
        }
    }

    private Fixture committedFixture() {
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
        return transaction.execute(status -> {
            Long sourceUserId = createUser("source");
            Long targetUserId = createUser("target");
            Long sourcePetId = createPet(sourceUserId, "source");
            Long targetPetId = createPet(targetUserId, "target");
            jdbcTemplate.update("""
                    INSERT INTO friendships (pet_low_id, pet_high_id)
                    VALUES (?, ?)
                    """,
                    Math.min(sourcePetId, targetPetId),
                    Math.max(sourcePetId, targetPetId)
            );
            return new Fixture(sourceUserId, sourcePetId, targetPetId);
        });
    }

    private Outcome delete(
            Fixture fixture,
            CountDownLatch readyLatch,
            CountDownLatch startLatch
    ) {
        readyLatch.countDown();
        try {
            if (!startLatch.await(10, TimeUnit.SECONDS)) {
                return Outcome.failure(
                        null,
                        new AssertionError("Timed out waiting for start latch")
                );
            }
            friendshipDeletionService.deleteFriendship(
                    fixture.sourceUserId(),
                    fixture.sourcePetId(),
                    fixture.targetPetId()
            );
            return Outcome.successful();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Outcome.failure(null, exception);
        } catch (Throwable failure) {
            BusinessException businessException =
                    findBusinessException(failure);
            return Outcome.failure(
                    businessException == null
                            ? null
                            : businessException.getErrorCode(),
                    businessException == null ? failure : businessException
            );
        }
    }

    private BusinessException findBusinessException(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof BusinessException businessException) {
                return businessException;
            }
            current = current.getCause();
        }
        return null;
    }

    private long friendshipCount(Long firstPetId, Long secondPetId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM friendships
                 WHERE pet_low_id = ?
                   AND pet_high_id = ?
                """,
                Long.class,
                Math.min(firstPetId, secondPetId),
                Math.max(firstPetId, secondPetId)
        );
    }

    private Long createUser(String prefix) {
        String unique = unique();
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (
                    email,
                    password_hash,
                    nickname,
                    public_tag,
                    role,
                    account_status,
                    neighborhood_code
                ) VALUES (?, 'encoded', ?, ?, 'USER', 'ACTIVE', '4113165000')
                RETURNING id
                """,
                Long.class,
                prefix + unique + "@example.com",
                prefix,
                prefix + "#" + unique.substring(0, 8)
        );
    }

    private Long createPet(Long ownerUserId, String prefix) {
        String unique = unique();
        return jdbcTemplate.queryForObject("""
                INSERT INTO pets (
                    owner_user_id,
                    public_tag,
                    nickname,
                    status
                ) VALUES (?, ?, ?, 'ACTIVE')
                RETURNING id
                """,
                Long.class,
                ownerUserId,
                prefix + "#" + unique.substring(0, 4).toUpperCase(),
                prefix
        );
    }

    private String unique() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record Fixture(
            Long sourceUserId,
            Long sourcePetId,
            Long targetPetId
    ) {
    }

    private record Outcome(
            boolean success,
            ErrorCode errorCode,
            Throwable failure
    ) {

        private static Outcome successful() {
            return new Outcome(true, null, null);
        }

        private static Outcome failure(
                ErrorCode errorCode,
                Throwable failure
        ) {
            return new Outcome(false, errorCode, failure);
        }
    }
}
