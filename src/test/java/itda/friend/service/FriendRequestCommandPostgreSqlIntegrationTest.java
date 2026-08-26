package itda.friend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRequestStatus;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
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
class FriendRequestCommandPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer(
                DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                        .asCompatibleSubstituteFor("postgres")
        );

    @Autowired
    private FriendRequestCommandService commandService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long sourceUserId;
    private Long targetUserId;
    private Long sourcePetId;
    private Long targetPetId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE chat_room_participants, chat_rooms, user_blocks,
                    friendships, friend_requests, pets, users
                RESTART IDENTITY CASCADE
                """);
        sourceUserId = createUser("source");
        targetUserId = createUser("target");
        sourcePetId = createPet(sourceUserId, "sourcePet");
        targetPetId = createPet(targetUserId, "targetPet");
        activatePet(sourceUserId, sourcePetId);
    }

    @Test
    void createsPendingRequestWithSevenDayExpiry() {
        FriendRequestCommandResult result =
                commandService.create(sourceUserId, targetPetId);

        assertThat(result.created()).isTrue();
        assertThat(result.response().status())
                .isEqualTo(FriendRequestStatus.PENDING);
        assertThat(result.response().respondedAt()).isNull();
        assertThat(result.response().directRoomId()).isNull();
        assertThat(result.response().expiresAt())
                .isEqualTo(result.response().requestedAt().plusSeconds(
                        7 * 24 * 60 * 60
                ));
        assertThat(count("friend_requests")).isEqualTo(1);
        assertThat(count("friendships")).isZero();
    }

    @Test
    void expiresOldPendingBeforeCreatingReplacement() {
        Long oldRequestId = insertFriendRequest(
                sourcePetId,
                targetPetId,
                "PENDING",
                Instant.now().minusSeconds(1)
        );

        FriendRequestCommandResult result =
                commandService.create(sourceUserId, targetPetId);

        assertThat(result.created()).isTrue();
        assertThat(status(oldRequestId)).isEqualTo("EXPIRED");
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM friend_requests
                WHERE status = 'PENDING'
                """,
                Integer.class
        )).isEqualTo(1);
        assertThat(count("friend_requests")).isEqualTo(2);
    }

    @Test
    void autoAcceptsReversePendingAndCreatesDirectRoomOnce() {
        Long requestId = insertFriendRequest(
                targetPetId,
                sourcePetId,
                "PENDING",
                Instant.now().plusSeconds(3600)
        );

        FriendRequestCommandResult result =
                commandService.create(sourceUserId, targetPetId);

        assertThat(result.created()).isFalse();
        assertThat(result.response().requestId()).isEqualTo(requestId);
        assertThat(result.response().requesterPet().petId())
                .isEqualTo(targetPetId);
        assertThat(result.response().targetPet().petId())
                .isEqualTo(sourcePetId);
        assertThat(result.response().status())
                .isEqualTo(FriendRequestStatus.ACCEPTED);
        assertThat(result.response().respondedAt()).isNotNull();
        assertThat(result.response().directRoomId()).isNotNull();
        assertThat(count("friend_requests")).isEqualTo(1);
        assertThat(count("friendships")).isEqualTo(1);
        assertThat(count("chat_rooms")).isEqualTo(1);
        assertThat(count("chat_room_participants")).isEqualTo(2);
    }

    @Test
    void autoAcceptReusesExistingGreetingRoomWithoutChangingItsState() {
        Long requestId = insertFriendRequest(
                targetPetId,
                sourcePetId,
                "PENDING",
                Instant.now().plusSeconds(3600)
        );
        Long roomId = jdbcTemplate.queryForObject("""
                INSERT INTO chat_rooms (
                    type,
                    status,
                    origin,
                    pet_low_id,
                    pet_high_id,
                    archived_at
                ) VALUES ('DIRECT', 'ARCHIVED', 'GREETING', ?, ?, now())
                RETURNING id
                """,
                Long.class,
                Math.min(sourcePetId, targetPetId),
                Math.max(sourcePetId, targetPetId)
        );

        FriendRequestCommandResult result =
                commandService.create(sourceUserId, targetPetId);

        assertThat(result.response().requestId()).isEqualTo(requestId);
        assertThat(result.response().directRoomId()).isEqualTo(roomId);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, origin FROM chat_rooms WHERE id = ?",
                roomId
        )).containsEntry("status", "ARCHIVED")
                .containsEntry("origin", "GREETING");
        assertThat(count("chat_rooms")).isEqualTo(1);
    }

    @Test
    void rejectsSameDirectionPendingWithoutChangingIt() {
        Long requestId = insertFriendRequest(
                sourcePetId,
                targetPetId,
                "PENDING",
                Instant.now().plusSeconds(3600)
        );
        OffsetDateTime updatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM friend_requests WHERE id = ?",
                OffsetDateTime.class,
                requestId
        );

        assertBusinessError(
                () -> commandService.create(sourceUserId, targetPetId),
                ErrorCode.FRIEND_REQUEST_ALREADY_PENDING
        );

        assertThat(status(requestId)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT updated_at FROM friend_requests WHERE id = ?",
                OffsetDateTime.class,
                requestId
        )).isEqualTo(updatedAt);
    }

    @Test
    void rejectsExistingFriendship() {
        insertFriendship(sourcePetId, targetPetId);

        assertBusinessError(
                () -> commandService.create(sourceUserId, targetPetId),
                ErrorCode.FRIENDSHIP_ALREADY_EXISTS
        );

        assertThat(count("friend_requests")).isZero();
    }

    @Test
    void doesNotApplyFriendLimitToNewPending() {
        createFriendshipsForSource(50);

        FriendRequestCommandResult result =
                commandService.create(sourceUserId, targetPetId);

        assertThat(result.created()).isTrue();
        assertThat(result.response().status())
                .isEqualTo(FriendRequestStatus.PENDING);
    }

    @Test
    void keepsReversePendingWhenAutoAcceptExceedsFriendLimit() {
        createFriendshipsForSource(50);
        Long requestId = insertFriendRequest(
                targetPetId,
                sourcePetId,
                "PENDING",
                Instant.now().plusSeconds(3600)
        );

        assertBusinessError(
                () -> commandService.create(sourceUserId, targetPetId),
                ErrorCode.FRIEND_LIMIT_EXCEEDED
        );

        assertThat(status(requestId)).isEqualTo("PENDING");
        assertThat(count("chat_rooms")).isZero();
    }

    @Test
    void rollsBackFriendStateWhenDirectRoomCreationFails() {
        Long requestId = insertFriendRequest(
                targetPetId,
                sourcePetId,
                "PENDING",
                Instant.now().plusSeconds(3600)
        );
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_direct_room_insert()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'forced direct room failure';
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_direct_room_insert_trigger
                BEFORE INSERT ON chat_rooms
                FOR EACH ROW
                EXECUTE FUNCTION fail_direct_room_insert()
                """);

        try {
            assertThatThrownBy(() ->
                    commandService.create(sourceUserId, targetPetId)
            ).isInstanceOf(DataAccessException.class);
        } finally {
            jdbcTemplate.execute(
                    "DROP TRIGGER fail_direct_room_insert_trigger ON chat_rooms"
            );
            jdbcTemplate.execute("DROP FUNCTION fail_direct_room_insert()");
        }

        assertThat(status(requestId)).isEqualTo("PENDING");
        assertThat(count("friendships")).isZero();
        assertThat(count("chat_rooms")).isZero();
    }

    private void createFriendshipsForSource(int friendCount) {
        for (int index = 0; index < friendCount; index++) {
            Long counterpartUserId = createUser("friend" + index);
            Long counterpartPetId = createPet(
                    counterpartUserId,
                    "friendPet" + index
            );
            insertFriendship(sourcePetId, counterpartPetId);
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

    private void activatePet(Long userId, Long petId) {
        jdbcTemplate.update(
                "UPDATE users SET active_pet_id = ? WHERE id = ?",
                petId,
                userId
        );
    }

    private Long insertFriendRequest(
            Long requesterPetId,
            Long targetPetId,
            String status,
            Instant expiresAt
    ) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO friend_requests (
                    requester_pet_id,
                    target_pet_id,
                    status,
                    expires_at
                ) VALUES (?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                requesterPetId,
                targetPetId,
                status,
                expiresAt.atOffset(ZoneOffset.UTC)
        );
    }

    private void insertFriendship(Long petAId, Long petBId) {
        jdbcTemplate.update("""
                INSERT INTO friendships (pet_low_id, pet_high_id)
                VALUES (?, ?)
                """,
                Math.min(petAId, petBId),
                Math.max(petAId, petBId)
        );
    }

    private String status(Long requestId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM friend_requests WHERE id = ?",
                String.class,
                requestId
        );
    }

    private int count(String table) {
        return switch (table) {
            case "friend_requests" -> jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM friend_requests",
                    Integer.class
            );
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
