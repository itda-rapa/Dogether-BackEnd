package itda.friend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.chat.service.ChatRoomService;
import itda.pet.service.query.PetDisplayQueryService;
import java.time.Instant;
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
class FriendResponseRollbackPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private FriendRequestCommandService commandService;

    @MockitoSpyBean
    private PetDisplayQueryService petDisplayQueryService;

    @MockitoSpyBean
    private ChatRoomService chatRoomService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long sourceUserId;
    private Long targetUserId;
    private Long sourcePetId;
    private Long targetPetId;

    @BeforeEach
    void setUp() {
        reset(petDisplayQueryService, chatRoomService);
        jdbcTemplate.execute("""
                TRUNCATE chat_room_participants, chat_rooms, user_blocks,
                    friendships, friend_requests, pets, users
                RESTART IDENTITY CASCADE
                """);
        sourceUserId = createUser("source");
        targetUserId = createUser("target");
        sourcePetId = createPet(sourceUserId, "sourcePet");
        targetPetId = createPet(targetUserId, "targetPet");
        jdbcTemplate.update(
                "UPDATE users SET active_pet_id=? WHERE id=?",
                sourcePetId,
                sourceUserId
        );
        jdbcTemplate.update(
                "UPDATE users SET active_pet_id=? WHERE id=?",
                targetPetId,
                targetUserId
        );
    }

    @Test
    void rollsBackAcceptedFriendshipAndRoomWhenResponseLookupFails() {
        insertReversePending();
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR))
                .when(petDisplayQueryService)
                .getPetDisplaySummaries(any());

        assertThatThrownBy(() ->
                commandService.create(sourceUserId, targetPetId)
        ).isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        verify(petDisplayQueryService).getPetDisplaySummaries(any());
        assertThat(countFriendRequestsForPair()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM friend_requests",
                String.class
        )).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM friendships",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_rooms",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_room_participants",
                Integer.class
        )).isZero();
    }

    @Test
    void rollsBackNewPendingWhenResponseLookupFails() {
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR))
                .when(petDisplayQueryService)
                .getPetDisplaySummaries(any());

        assertThatThrownBy(() ->
                commandService.create(sourceUserId, targetPetId)
        ).isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        verify(petDisplayQueryService).getPetDisplaySummaries(any());
        assertThat(countFriendRequestsForPair()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM friend_requests
                WHERE status = 'PENDING'
                """,
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM friendships",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_rooms",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_room_participants",
                Integer.class
        )).isZero();
    }

    @Test
    void rollsBackExplicitAcceptanceAndRoomWhenResponseLookupFails() {
        Long requestId = insertForwardPending();
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR))
                .when(petDisplayQueryService)
                .getPetDisplaySummaries(any());

        assertThatThrownBy(() ->
                commandService.accept(targetUserId, requestId)
        ).isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        verify(petDisplayQueryService).getPetDisplaySummaries(any());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM friend_requests WHERE id = ?",
                String.class,
                requestId
        )).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM friendships",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_rooms",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_room_participants",
                Integer.class
        )).isZero();
    }

    @Test
    void rollsBackExplicitAcceptanceWhenDirectRoomCreationFails() {
        Long requestId = insertForwardPending();
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR))
                .when(chatRoomService)
                .ensureDirectRoom(anyLong(), anyLong(), any());

        assertThatThrownBy(() ->
                commandService.accept(targetUserId, requestId)
        ).isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        verify(chatRoomService).ensureDirectRoom(
                anyLong(),
                anyLong(),
                any()
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM friend_requests WHERE id = ?",
                String.class,
                requestId
        )).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM friendships",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_rooms",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_room_participants",
                Integer.class
        )).isZero();
    }

    @Test
    void rollsBackRejectionWhenResponseLookupFails() {
        Long requestId = insertForwardPending();
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR))
                .when(petDisplayQueryService)
                .getPetDisplaySummaries(any());

        assertThatThrownBy(() ->
                commandService.reject(targetUserId, requestId)
        ).isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        verify(petDisplayQueryService).getPetDisplaySummaries(any());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM friend_requests WHERE id = ?",
                String.class,
                requestId
        )).isEqualTo("PENDING");
    }

    private void insertReversePending() {
        jdbcTemplate.update("""
                INSERT INTO friend_requests (
                    requester_pet_id, target_pet_id, status, expires_at
                ) VALUES (?, ?, 'PENDING', ?)
                """,
                targetPetId,
                sourcePetId,
                Instant.now().plusSeconds(3600).atOffset(ZoneOffset.UTC)
        );
    }

    private Long insertForwardPending() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO friend_requests (
                    requester_pet_id, target_pet_id, status, expires_at
                ) VALUES (?, ?, 'PENDING', ?)
                RETURNING id
                """,
                Long.class,
                sourcePetId,
                targetPetId,
                Instant.now().plusSeconds(3600).atOffset(ZoneOffset.UTC)
        );
    }

    private int countFriendRequestsForPair() {
        long petLowId = Math.min(sourcePetId, targetPetId);
        long petHighId = Math.max(sourcePetId, targetPetId);
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM friend_requests
                WHERE pair_low_id = ?
                  AND pair_high_id = ?
                """,
                Integer.class,
                petLowId,
                petHighId
        );
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

    private String unique() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
