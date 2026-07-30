package itda.friend.service;

import static org.assertj.core.api.Assertions.assertThat;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class FriendRequestConcurrencyPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private FriendRequestCommandService commandService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;
    private Long userA;
    private Long userB;
    private Long petA;
    private Long petB;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE chat_room_participants, chat_rooms, user_blocks,
                    friendships, friend_requests, pets, users
                RESTART IDENTITY CASCADE
                """);
        userA = createUser("userA");
        userB = createUser("userB");
        petA = createPet(userA, "petA");
        petB = createPet(userB, "petB");
        activatePet(userA, petA);
        activatePet(userB, petB);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void concurrentSameDirectionCreatesOnePending() throws Exception {
        List<CommandOutcome> outcomes = runTogether(
                () -> invoke(userA, petB),
                () -> invoke(userA, petB)
        );

        assertThat(outcomes.stream().filter(CommandOutcome::created).count())
                .isEqualTo(1);
        assertThat(outcomes.stream().map(CommandOutcome::errorCode))
                .containsExactlyInAnyOrder(
                        null,
                        ErrorCode.FRIEND_REQUEST_ALREADY_PENDING
                );
        assertThat(activePendingCount()).isEqualTo(1);
        assertThat(count("friendships")).isZero();
    }

    @Test
    void concurrentReverseDirectionsConvergeToFriendship() throws Exception {
        List<CommandOutcome> outcomes = runTogether(
                () -> invoke(userA, petB),
                () -> invoke(userB, petA)
        );

        assertThat(outcomes.stream().filter(CommandOutcome::created).count())
                .isEqualTo(1);
        assertThat(outcomes.stream().filter(CommandOutcome::accepted).count())
                .isEqualTo(1);
        assertThat(outcomes).allMatch(outcome -> outcome.errorCode() == null);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM friend_requests WHERE status='ACCEPTED'",
                Integer.class
        )).isEqualTo(1);
        assertThat(count("friend_requests")).isEqualTo(1);
        assertThat(count("friendships")).isEqualTo(1);
        assertThat(count("chat_rooms")).isEqualTo(1);
        assertThat(count("chat_room_participants")).isEqualTo(2);
    }

    @Test
    void concurrentAutoAcceptsAtFortyNineAllowOnlyOne() throws Exception {
        createFriendshipsForPetA(49);
        Long userC = createUser("userC");
        Long petC = createPet(userC, "petC");
        activatePet(userC, petC);
        insertPending(petB, petA);
        insertPending(petC, petA);

        List<CommandOutcome> outcomes = runTogether(
                () -> invoke(userA, petB),
                () -> invoke(userA, petC)
        );

        assertThat(outcomes.stream().filter(CommandOutcome::accepted).count())
                .isEqualTo(1);
        assertThat(outcomes.stream().map(CommandOutcome::errorCode))
                .containsExactlyInAnyOrder(
                        null,
                        ErrorCode.FRIEND_LIMIT_EXCEEDED
                );
        assertThat(friendCount(petA)).isEqualTo(50);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM friend_requests WHERE status='PENDING'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM friend_requests WHERE status='ACCEPTED'",
                Integer.class
        )).isEqualTo(1);
    }

    private List<CommandOutcome> runTogether(
            Callable<CommandOutcome> first,
            Callable<CommandOutcome> second
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<CommandOutcome> firstFuture = executor.submit(() -> {
            ready.countDown();
            await(start);
            return first.call();
        });
        Future<CommandOutcome> secondFuture = executor.submit(() -> {
            ready.countDown();
            await(start);
            return second.call();
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        return List.of(
                firstFuture.get(20, TimeUnit.SECONDS),
                secondFuture.get(20, TimeUnit.SECONDS)
        );
    }

    private CommandOutcome invoke(Long userId, Long targetPetId) {
        try {
            FriendRequestCommandResult result =
                    commandService.create(userId, targetPetId);
            return new CommandOutcome(
                    result.created(),
                    !result.created(),
                    null
            );
        } catch (BusinessException exception) {
            return new CommandOutcome(
                    false,
                    false,
                    exception.getErrorCode()
            );
        }
    }

    private void createFriendshipsForPetA(int count) {
        for (int index = 0; index < count; index++) {
            Long userId = createUser("friend" + index);
            Long petId = createPet(userId, "friendPet" + index);
            insertFriendship(petA, petId);
        }
    }

    private Long createUser(String prefix) {
        String unique = unique();
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (
                    email, password_hash, nickname, public_tag,
                    role, account_status, neighborhood_code
                ) VALUES (?, 'encoded', ?, ?, 'USER', 'ACTIVE', '4113111500')
                RETURNING id
                """,
                Long.class,
                prefix + unique + "@example.com",
                prefix,
                prefix + "#" + unique.substring(0, 8)
        );
    }

    private Long createPet(Long ownerId, String prefix) {
        String unique = unique();
        return jdbcTemplate.queryForObject("""
                INSERT INTO pets (
                    owner_user_id, public_tag, nickname, status
                ) VALUES (?, ?, ?, 'ACTIVE')
                RETURNING id
                """,
                Long.class,
                ownerId,
                prefix + "#" + unique.substring(0, 4).toUpperCase(),
                prefix
        );
    }

    private void activatePet(Long userId, Long petId) {
        jdbcTemplate.update(
                "UPDATE users SET active_pet_id=? WHERE id=?",
                petId,
                userId
        );
    }

    private void insertPending(Long requesterPetId, Long targetPetId) {
        jdbcTemplate.update("""
                INSERT INTO friend_requests (
                    requester_pet_id, target_pet_id, status, expires_at
                ) VALUES (?, ?, 'PENDING', ?)
                """,
                requesterPetId,
                targetPetId,
                Instant.now().plusSeconds(3600).atOffset(ZoneOffset.UTC)
        );
    }

    private void insertFriendship(Long firstPetId, Long secondPetId) {
        jdbcTemplate.update("""
                INSERT INTO friendships (pet_low_id, pet_high_id)
                VALUES (?, ?)
                """,
                Math.min(firstPetId, secondPetId),
                Math.max(firstPetId, secondPetId)
        );
    }

    private int activePendingCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM friend_requests WHERE status='PENDING'",
                Integer.class
        );
    }

    private int friendCount(Long petId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM friendships
                WHERE pet_low_id=? OR pet_high_id=?
                """,
                Integer.class,
                petId,
                petId
        );
    }

    private int count(String table) {
        return switch (table) {
            case "friend_requests" -> jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM friend_requests", Integer.class);
            case "friendships" -> jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM friendships", Integer.class);
            case "chat_rooms" -> jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM chat_rooms", Integer.class);
            case "chat_room_participants" -> jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM chat_room_participants",
                    Integer.class);
            default -> throw new IllegalArgumentException("Unknown table");
        };
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private String unique() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record CommandOutcome(
            boolean created,
            boolean accepted,
            ErrorCode errorCode
    ) {
    }
}
