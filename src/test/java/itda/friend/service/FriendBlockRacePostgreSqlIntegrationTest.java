package itda.friend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import itda.block.dto.BlockCreateRequest;
import itda.block.service.BlockRelationshipQueryService;
import itda.block.service.BlockService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
class FriendBlockRacePostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private FriendRequestCommandService commandService;

    @Autowired
    private BlockService blockService;

    @MockitoSpyBean
    private BlockRelationshipQueryService blockRelationshipQueryService;

    @MockitoSpyBean
    private FriendBlockCleanupService friendBlockCleanupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;
    private Long userA;
    private Long userB;
    private Long petA;
    private Long petB;

    @BeforeEach
    void setUp() {
        reset(blockRelationshipQueryService, friendBlockCleanupService);
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
    void friendFirstPendingIsCleanedByFollowingBlock() throws Exception {
        RaceResult race = runFriendFirst(false);

        assertThat(race.friend().created()).isTrue();
        assertThat(race.blockFailure()).isNull();
        assertBlockedWithoutActiveFriendState();
        assertThat(statusCounts("CANCELED")).isEqualTo(1);
    }

    @Test
    void blockFirstPreventsNewPending() throws Exception {
        RaceResult race = runBlockFirst(false);

        assertThat(race.blockFailure()).isNull();
        assertThat(race.friendFailure())
                .isEqualTo(ErrorCode.BLOCKED_USER);
        assertBlockedWithoutActiveFriendState();
        assertThat(count("friend_requests")).isZero();
    }

    @Test
    void friendFirstAutoAcceptPreservesRoomButCleanupRemovesFriendship()
            throws Exception {
        insertPending(petB, petA);

        RaceResult race = runFriendFirst(true);

        assertThat(race.friend().created()).isFalse();
        assertThat(race.blockFailure()).isNull();
        assertBlockedWithoutActiveFriendState();
        assertThat(statusCounts("ACCEPTED")).isEqualTo(1);
        assertThat(count("chat_rooms")).isEqualTo(1);
        assertThat(count("chat_room_participants")).isEqualTo(2);
    }

    @Test
    void blockFirstCancelsReversePendingAndPreventsAutoAccept()
            throws Exception {
        insertPending(petB, petA);

        RaceResult race = runBlockFirst(true);

        assertThat(race.blockFailure()).isNull();
        assertThat(race.friendFailure())
                .isEqualTo(ErrorCode.BLOCKED_USER);
        assertBlockedWithoutActiveFriendState();
        assertThat(statusCounts("CANCELED")).isEqualTo(1);
        assertThat(count("chat_rooms")).isZero();
    }

    @Test
    void explicitAcceptFirstIsCleanedByFollowingBlock() throws Exception {
        Long requestId = insertPending(petA, petB);

        ActionRaceResult race = runAcceptFirst(requestId);

        assertThat(race.acceptFailure()).isNull();
        assertThat(race.blockFailure()).isNull();
        assertBlockedWithoutActiveFriendState();
        assertThat(statusCounts("ACCEPTED")).isEqualTo(1);
        assertThat(count("chat_rooms")).isEqualTo(1);
        assertThat(count("chat_room_participants")).isEqualTo(2);
    }

    @Test
    void blockFirstPreventsExplicitAccept() throws Exception {
        Long requestId = insertPending(petA, petB);

        ActionRaceResult race = runBlockBeforeAccept(requestId);

        assertThat(race.blockFailure()).isNull();
        assertThat(businessError(race.acceptFailure()))
                .isEqualTo(ErrorCode.FRIEND_REQUEST_NOT_PENDING);
        assertBlockedWithoutActiveFriendState();
        assertThat(statusCounts("CANCELED")).isEqualTo(1);
        assertThat(count("chat_rooms")).isZero();
    }

    private RaceResult runFriendFirst(boolean autoAccept) throws Exception {
        CountDownLatch friendPaused = new CountDownLatch(1);
        CountDownLatch releaseFriend = new CountDownLatch(1);
        CountDownLatch blockStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("friend-first")) {
                friendPaused.countDown();
                await(releaseFriend);
            }
            return invocation.callRealMethod();
        }).when(blockRelationshipQueryService)
                .existsBlockBetween(anyLong(), anyLong());

        Future<FriendCall> friendFuture = executor.submit(() -> {
            Thread.currentThread().setName("friend-first");
            return invokeFriend();
        });
        assertThat(friendPaused.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Throwable> blockFuture = executor.submit(() -> {
            Thread.currentThread().setName("block-after-friend");
            blockStarted.countDown();
            return invokeBlock();
        });
        assertThat(blockStarted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> blockFuture.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        releaseFriend.countDown();
        FriendCall friend = friendFuture.get(20, TimeUnit.SECONDS);
        Throwable blockFailure = blockFuture.get(20, TimeUnit.SECONDS);
        assertThat(friend.failure()).isNull();
        if (autoAccept) {
            assertThat(friend.result().created()).isFalse();
        }
        return new RaceResult(
                friend.result(),
                null,
                blockFailure
        );
    }

    private RaceResult runBlockFirst(boolean reversePending) throws Exception {
        CountDownLatch blockPaused = new CountDownLatch(1);
        CountDownLatch releaseBlock = new CountDownLatch(1);
        CountDownLatch friendStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("block-first")) {
                blockPaused.countDown();
                await(releaseBlock);
            }
            return invocation.callRealMethod();
        }).when(friendBlockCleanupService)
                .cleanupBetweenUsers(anyLong(), anyLong());

        Future<Throwable> blockFuture = executor.submit(() -> {
            Thread.currentThread().setName("block-first");
            return invokeBlock();
        });
        assertThat(blockPaused.await(10, TimeUnit.SECONDS)).isTrue();

        Future<FriendCall> friendFuture = executor.submit(() -> {
            Thread.currentThread().setName("friend-after-block");
            friendStarted.countDown();
            return invokeFriend();
        });
        assertThat(friendStarted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> friendFuture.get(
                300,
                TimeUnit.MILLISECONDS
        )).isInstanceOf(TimeoutException.class);

        releaseBlock.countDown();
        Throwable blockFailure = blockFuture.get(20, TimeUnit.SECONDS);
        FriendCall friend = friendFuture.get(20, TimeUnit.SECONDS);
        assertThat(friend.result()).isNull();
        if (reversePending) {
            assertThat(statusCounts("CANCELED")).isEqualTo(1);
        }
        return new RaceResult(
                null,
                businessError(friend.failure()),
                blockFailure
        );
    }

    private ActionRaceResult runAcceptFirst(Long requestId) throws Exception {
        CountDownLatch acceptPaused = new CountDownLatch(1);
        CountDownLatch releaseAccept = new CountDownLatch(1);
        CountDownLatch blockStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("accept-first")) {
                acceptPaused.countDown();
                await(releaseAccept);
            }
            return invocation.callRealMethod();
        }).when(blockRelationshipQueryService)
                .existsBlockBetween(anyLong(), anyLong());

        Future<Throwable> acceptFuture = executor.submit(() -> {
            Thread.currentThread().setName("accept-first");
            return invokeAccept(requestId);
        });
        assertThat(acceptPaused.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Throwable> blockFuture = executor.submit(() -> {
            Thread.currentThread().setName("block-after-accept");
            blockStarted.countDown();
            return invokeBlock();
        });
        assertThat(blockStarted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> blockFuture.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        releaseAccept.countDown();
        return new ActionRaceResult(
                acceptFuture.get(20, TimeUnit.SECONDS),
                blockFuture.get(20, TimeUnit.SECONDS)
        );
    }

    private ActionRaceResult runBlockBeforeAccept(Long requestId)
            throws Exception {
        CountDownLatch blockPaused = new CountDownLatch(1);
        CountDownLatch releaseBlock = new CountDownLatch(1);
        CountDownLatch acceptStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("block-first")) {
                blockPaused.countDown();
                await(releaseBlock);
            }
            return invocation.callRealMethod();
        }).when(friendBlockCleanupService)
                .cleanupBetweenUsers(anyLong(), anyLong());

        Future<Throwable> blockFuture = executor.submit(() -> {
            Thread.currentThread().setName("block-first");
            return invokeBlock();
        });
        assertThat(blockPaused.await(10, TimeUnit.SECONDS)).isTrue();

        Future<Throwable> acceptFuture = executor.submit(() -> {
            Thread.currentThread().setName("accept-after-block");
            acceptStarted.countDown();
            return invokeAccept(requestId);
        });
        assertThat(acceptStarted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> acceptFuture.get(
                300,
                TimeUnit.MILLISECONDS
        )).isInstanceOf(TimeoutException.class);

        releaseBlock.countDown();
        return new ActionRaceResult(
                acceptFuture.get(20, TimeUnit.SECONDS),
                blockFuture.get(20, TimeUnit.SECONDS)
        );
    }

    private FriendCall invokeFriend() {
        try {
            return new FriendCall(
                    commandService.create(userA, petB),
                    null
            );
        } catch (Throwable failure) {
            return new FriendCall(null, failure);
        }
    }

    private Throwable invokeBlock() {
        try {
            blockService.block(userA, new BlockCreateRequest(petB));
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private Throwable invokeAccept(Long requestId) {
        try {
            commandService.accept(userB, requestId);
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private ErrorCode businessError(Throwable failure) {
        assertThat(failure).isInstanceOf(BusinessException.class);
        return ((BusinessException) failure).getErrorCode();
    }

    private void assertBlockedWithoutActiveFriendState() {
        assertThat(count("user_blocks")).isEqualTo(1);
        assertThat(statusCounts("PENDING")).isZero();
        assertThat(count("friendships")).isZero();
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

    private Long insertPending(Long requesterPetId, Long targetPetId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO friend_requests (
                    requester_pet_id, target_pet_id, status, expires_at
                ) VALUES (?, ?, 'PENDING', ?)
                RETURNING id
                """,
                Long.class,
                requesterPetId,
                targetPetId,
                Instant.now().plusSeconds(3600).atOffset(ZoneOffset.UTC)
        );
    }

    private int statusCounts(String status) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM friend_requests WHERE status=?",
                Integer.class,
                status
        );
    }

    private int count(String table) {
        return switch (table) {
            case "user_blocks" -> jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_blocks", Integer.class);
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

    private record FriendCall(
            FriendRequestCommandResult result,
            Throwable failure
    ) {
    }

    private record RaceResult(
            FriendRequestCommandResult friend,
            ErrorCode friendFailure,
            Throwable blockFailure
    ) {
    }

    private record ActionRaceResult(
            Throwable acceptFailure,
            Throwable blockFailure
    ) {
    }
}
