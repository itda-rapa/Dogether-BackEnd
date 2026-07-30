package itda.friend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRequestStatus;
import itda.friend.dto.response.FriendRequestResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
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
class FriendRequestActionPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private FriendRequestCommandService commandService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long requesterUserId;
    private Long targetUserId;
    private Long requesterPetId;
    private Long targetPetId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE chat_room_participants, chat_rooms, user_blocks,
                    friendships, friend_requests, pets, users
                RESTART IDENTITY CASCADE
                """);
        requesterUserId = createUser("requester");
        targetUserId = createUser("target");
        requesterPetId = createPet(requesterUserId, "requesterPet");
        targetPetId = createPet(targetUserId, "targetPet");
        activatePet(requesterUserId, requesterPetId);
        activatePet(targetUserId, targetPetId);
    }

    @Test
    void acceptsPendingRequestAndCreatesFriendshipAndDirectRoom() {
        Long requestId = insertPending(Instant.now().plusSeconds(3600));

        FriendRequestResponse response =
                commandService.accept(targetUserId, requestId);

        assertThat(response.requestId()).isEqualTo(requestId);
        assertThat(response.status()).isEqualTo(FriendRequestStatus.ACCEPTED);
        assertThat(response.respondedAt()).isNotNull();
        assertThat(response.directRoomId()).isNotNull();
        assertThat(status(requestId)).isEqualTo("ACCEPTED");
        assertThat(respondedAt(requestId)).isNotNull();
        assertThat(count("friendships")).isEqualTo(1);
        assertThat(count("chat_rooms")).isEqualTo(1);
        assertThat(count("chat_room_participants")).isEqualTo(2);
    }

    @Test
    void rejectsPendingRequestWithoutCreatingRelationship() {
        Long requestId = insertPending(Instant.now().plusSeconds(3600));

        FriendRequestResponse response =
                commandService.reject(targetUserId, requestId);

        assertThat(response.status()).isEqualTo(FriendRequestStatus.REJECTED);
        assertThat(response.respondedAt()).isNotNull();
        assertThat(response.directRoomId()).isNull();
        assertThat(status(requestId)).isEqualTo("REJECTED");
        assertThat(respondedAt(requestId)).isNotNull();
        assertThat(count("friendships")).isZero();
        assertThat(count("chat_rooms")).isZero();
    }

    @Test
    void cancelsPendingRequestWithoutResponseDependencies() {
        Long requestId = insertPending(Instant.now().plusSeconds(3600));

        commandService.cancel(requesterUserId, requestId);

        assertThat(status(requestId)).isEqualTo("CANCELED");
        assertThat(respondedAt(requestId)).isNotNull();
        assertThat(count("friendships")).isZero();
        assertThat(count("chat_rooms")).isZero();
    }

    @Test
    void commitsExpirationBeforeReturningNotPending() {
        Long requestId = insertPending(Instant.now());

        assertBusinessError(
                () -> commandService.accept(targetUserId, requestId),
                ErrorCode.FRIEND_REQUEST_NOT_PENDING
        );

        assertThat(status(requestId)).isEqualTo("EXPIRED");
        assertThat(respondedAt(requestId)).isNull();
        assertThat(count("friendships")).isZero();
        assertThat(count("chat_rooms")).isZero();
    }

    @Test
    void rejectCommitsExpirationBeforeReturningNotPending() {
        Long requestId = insertPending(Instant.now());

        assertBusinessError(
                () -> commandService.reject(targetUserId, requestId),
                ErrorCode.FRIEND_REQUEST_NOT_PENDING
        );

        assertThat(status(requestId)).isEqualTo("EXPIRED");
        assertThat(respondedAt(requestId)).isNull();
        assertThat(count("friendships")).isZero();
        assertThat(count("chat_rooms")).isZero();
    }

    @Test
    void cancelCommitsExpirationBeforeReturningNotPending() {
        Long requestId = insertPending(Instant.now());

        assertBusinessError(
                () -> commandService.cancel(requesterUserId, requestId),
                ErrorCode.FRIEND_REQUEST_NOT_PENDING
        );

        assertThat(status(requestId)).isEqualTo("EXPIRED");
        assertThat(respondedAt(requestId)).isNull();
        assertThat(count("friendships")).isZero();
        assertThat(count("chat_rooms")).isZero();
    }

    @Test
    void hidesRequestFromUnrelatedActivePet() {
        Long requestId = insertPending(Instant.now().plusSeconds(3600));
        Long otherUserId = createUser("other");
        Long otherPetId = createPet(otherUserId, "otherPet");
        activatePet(otherUserId, otherPetId);

        assertBusinessError(
                () -> commandService.accept(otherUserId, requestId),
                ErrorCode.FRIEND_REQUEST_NOT_FOUND
        );

        assertThat(status(requestId)).isEqualTo("PENDING");
    }

    @Test
    void blocksAcceptanceButAllowsRejectAndCancelCleanupActions() {
        Long acceptRequestId = insertPending(
                Instant.now().plusSeconds(3600)
        );
        insertBlock();

        assertBusinessError(
                () -> commandService.accept(targetUserId, acceptRequestId),
                ErrorCode.BLOCKED_USER
        );
        assertThat(status(acceptRequestId)).isEqualTo("PENDING");

        commandService.reject(targetUserId, acceptRequestId);
        assertThat(status(acceptRequestId)).isEqualTo("REJECTED");

        jdbcTemplate.update("DELETE FROM user_blocks");
        Long cancelRequestId = insertPending(
                Instant.now().plusSeconds(3600)
        );
        insertBlock();
        commandService.cancel(requesterUserId, cancelRequestId);
        assertThat(status(cancelRequestId)).isEqualTo("CANCELED");
    }

    @Test
    void rejectAllowsSuspendedRequesterUser() {
        Long requestId = insertPending(Instant.now().plusSeconds(3600));
        jdbcTemplate.update(
                "UPDATE users SET account_status = 'SUSPENDED' WHERE id = ?",
                requesterUserId
        );

        FriendRequestResponse response =
                commandService.reject(targetUserId, requestId);

        assertThat(response.status()).isEqualTo(FriendRequestStatus.REJECTED);
        assertThat(status(requestId)).isEqualTo("REJECTED");
        assertThat(respondedAt(requestId)).isNotNull();
        assertThat(count("friendships")).isZero();
        assertThat(count("chat_rooms")).isZero();
    }

    @Test
    void rejectAllowsDeletedRequesterPet() {
        Long requestId = insertPending(Instant.now().plusSeconds(3600));
        jdbcTemplate.update("""
                UPDATE pets
                   SET status = 'DELETED',
                       deleted_at = now()
                 WHERE id = ?
                """,
                requesterPetId
        );

        FriendRequestResponse response =
                commandService.reject(targetUserId, requestId);

        assertThat(response.status()).isEqualTo(FriendRequestStatus.REJECTED);
        assertThat(status(requestId)).isEqualTo("REJECTED");
        assertThat(respondedAt(requestId)).isNotNull();
        assertThat(count("friendships")).isZero();
        assertThat(count("chat_rooms")).isZero();
    }

    @Test
    void cancelAllowsSuspendedTargetUserAndPet() {
        Long requestId = insertPending(Instant.now().plusSeconds(3600));
        jdbcTemplate.update(
                "UPDATE users SET account_status = 'SUSPENDED' WHERE id = ?",
                targetUserId
        );
        jdbcTemplate.update(
                "UPDATE pets SET status = 'SUSPENDED' WHERE id = ?",
                targetPetId
        );

        commandService.cancel(requesterUserId, requestId);

        assertThat(status(requestId)).isEqualTo("CANCELED");
        assertThat(respondedAt(requestId)).isNotNull();
        assertThat(count("friendships")).isZero();
        assertThat(count("chat_rooms")).isZero();
    }

    @Test
    void preservesPendingWhenFriendLimitIsReached() {
        createFriendshipsForTarget(50);
        Long requestId = insertPending(Instant.now().plusSeconds(3600));

        assertBusinessError(
                () -> commandService.accept(targetUserId, requestId),
                ErrorCode.FRIEND_LIMIT_EXCEEDED
        );

        assertThat(status(requestId)).isEqualTo("PENDING");
        assertThat(count("chat_rooms")).isZero();
    }

    private Long insertPending(Instant expiresAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO friend_requests (
                    requester_pet_id,
                    target_pet_id,
                    status,
                    expires_at
                ) VALUES (?, ?, 'PENDING', ?)
                RETURNING id
                """,
                Long.class,
                requesterPetId,
                targetPetId,
                expiresAt.atOffset(ZoneOffset.UTC)
        );
    }

    private void insertBlock() {
        jdbcTemplate.update("""
                INSERT INTO user_blocks (
                    blocker_user_id,
                    blocked_user_id,
                    source_pet_id,
                    target_pet_id
                ) VALUES (?, ?, ?, ?)
                """,
                requesterUserId,
                targetUserId,
                requesterPetId,
                targetPetId
        );
    }

    private void createFriendshipsForTarget(int friendCount) {
        for (int index = 0; index < friendCount; index++) {
            Long userId = createUser("friend" + index);
            Long petId = createPet(userId, "friendPet" + index);
            jdbcTemplate.update("""
                    INSERT INTO friendships (pet_low_id, pet_high_id)
                    VALUES (?, ?)
                    """,
                    Math.min(targetPetId, petId),
                    Math.max(targetPetId, petId)
            );
        }
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
                ) VALUES (?, 'encoded', ?, ?, 'USER', 'ACTIVE', '4113111500')
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

    private void activatePet(Long userId, Long petId) {
        jdbcTemplate.update(
                "UPDATE users SET active_pet_id = ? WHERE id = ?",
                petId,
                userId
        );
    }

    private String status(Long requestId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM friend_requests WHERE id = ?",
                String.class,
                requestId
        );
    }

    private OffsetDateTime respondedAt(Long requestId) {
        return jdbcTemplate.queryForObject(
                "SELECT responded_at FROM friend_requests WHERE id = ?",
                OffsetDateTime.class,
                requestId
        );
    }

    private int count(String table) {
        return switch (table) {
            case "friendships" -> jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM friendships",
                    Integer.class
            );
            case "chat_rooms" -> jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM chat_rooms",
                    Integer.class
            );
            case "chat_room_participants" -> jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM chat_room_participants",
                    Integer.class
            );
            default -> throw new IllegalArgumentException("Unknown table");
        };
    }

    private void assertBusinessError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            ErrorCode expected
    ) {
        assertThatThrownBy(operation)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(expected);
    }

    private String unique() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
