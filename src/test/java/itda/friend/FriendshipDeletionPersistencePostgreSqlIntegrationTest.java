package itda.friend;

import static org.assertj.core.api.Assertions.assertThat;

import itda.chat.domain.RoomOrigin;
import itda.chat.dto.ChatMessageCreateRequest;
import itda.chat.service.ChatQueryService;
import itda.chat.service.ChatRoomService;
import itda.friend.domain.FriendRelationship;
import itda.friend.dto.response.PetFriendListResponse;
import itda.friend.service.FriendshipDeletionService;
import itda.friend.service.query.FriendRelationshipQueryService;
import itda.friend.service.query.FriendshipQueryService;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
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
class FriendshipDeletionPersistencePostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private FriendshipDeletionService friendshipDeletionService;

    @Autowired
    private FriendshipQueryService friendshipQueryService;

    @Autowired
    private FriendRelationshipQueryService friendRelationshipQueryService;

    @Autowired
    private ChatRoomService chatRoomService;

    @Autowired
    private ChatQueryService chatQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long sourceUserId;
    private Long targetUserId;
    private Long sourcePetId;
    private Long targetPetId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE chat_messages, chat_room_participants, chat_rooms,
                    greetings, setlog_reactions, setlogs, media, user_blocks,
                    friendships, friend_requests, pets, users
                RESTART IDENTITY CASCADE
                """);
        sourceUserId = createUser("source");
        targetUserId = createUser("target");
        sourcePetId = createPet(sourceUserId, "source");
        targetPetId = createPet(targetUserId, "target");
        setActivePet(sourceUserId, sourcePetId);
        setActivePet(targetUserId, targetPetId);
    }

    @Test
    void deletesOnlyRequestedPairAndRefreshesFriendViewsWhilePreservingHistory() {
        Long otherUserId = createUser("other");
        Long otherPetId = createPet(otherUserId, "other");
        insertFriendship(sourcePetId, targetPetId);
        insertFriendship(sourcePetId, otherPetId);
        Long roomId = createDirectRoom();
        Long requestId = insertAcceptedFriendRequest();
        Long greetingId = insertRespondedGreeting(roomId);
        Map<String, Object> requestBefore = friendRequestSnapshot(requestId);
        Map<String, Object> greetingBefore = greetingSnapshot(greetingId);

        friendshipDeletionService.deleteFriendship(
                sourceUserId,
                sourcePetId,
                targetPetId
        );

        assertThat(friendshipCount(sourcePetId, targetPetId)).isZero();
        assertThat(friendshipCount(sourcePetId, otherPetId)).isOne();
        PetFriendListResponse friends = friendshipQueryService.listFriends(
                sourceUserId,
                sourcePetId,
                null,
                20
        );
        assertThat(friends.items())
                .extracting(item -> item.petId())
                .containsExactly(otherPetId);
        assertThat(friendRelationshipQueryService.getRelationships(
                sourcePetId,
                java.util.List.of(targetPetId)
        )).containsEntry(targetPetId, FriendRelationship.NONE);
        assertThat(friendRequestSnapshot(requestId)).isEqualTo(requestBefore);
        assertThat(greetingSnapshot(greetingId)).isEqualTo(greetingBefore);
    }

    @Test
    void deletesExistingPairWhenTargetPetIsSuspended() {
        insertFriendship(sourcePetId, targetPetId);
        jdbcTemplate.update(
                "UPDATE pets SET status = 'SUSPENDED' WHERE id = ?",
                targetPetId
        );

        friendshipDeletionService.deleteFriendship(
                sourceUserId,
                sourcePetId,
                targetPetId
        );

        assertThat(friendshipCount(sourcePetId, targetPetId)).isZero();
    }

    @Test
    void deletesExistingPairWhenTargetPetIsSoftDeleted() {
        insertFriendship(sourcePetId, targetPetId);
        jdbcTemplate.update("""
                UPDATE pets
                   SET status = 'DELETED',
                       deleted_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, targetPetId);

        friendshipDeletionService.deleteFriendship(
                sourceUserId,
                sourcePetId,
                targetPetId
        );

        assertThat(friendshipCount(sourcePetId, targetPetId)).isZero();
    }

    @Test
    void deletesExistingPairWhenTargetOwnerIsInactive() {
        insertFriendship(sourcePetId, targetPetId);
        jdbcTemplate.update(
                "UPDATE users SET account_status = 'SUSPENDED' WHERE id = ?",
                targetUserId
        );

        friendshipDeletionService.deleteFriendship(
                sourceUserId,
                sourcePetId,
                targetPetId
        );

        assertThat(friendshipCount(sourcePetId, targetPetId)).isZero();
    }

    @Test
    void preservesDirectChatImmediatelyThenAllowsIntentionalMessageActivity() {
        insertFriendship(sourcePetId, targetPetId);
        Long roomId = createDirectRoom();
        Long requestId = insertAcceptedFriendRequest();
        Long greetingId = insertRespondedGreeting(roomId);
        ChatQueryService.SendMessageResult initial = chatQueryService.sendMessage(
                sourceUserId,
                roomId,
                new ChatMessageCreateRequest("before-delete", "기존 메시지")
        );
        jdbcTemplate.update("""
                UPDATE chat_rooms
                   SET last_message_at = TIMESTAMPTZ '2000-01-01 00:00:00Z',
                       updated_at = TIMESTAMPTZ '2000-01-01 00:00:00Z'
                 WHERE id = ?
                """, roomId);

        Map<String, Object> roomBefore = roomSnapshot(roomId);
        java.util.List<Map<String, Object>> participantsBefore =
                participantSnapshots(roomId);
        Map<String, Object> messageBefore =
                messageSnapshot(initial.data().messageId());
        int messageCountBefore = messageCount(roomId);
        Map<String, Object> requestBefore = friendRequestSnapshot(requestId);
        Map<String, Object> greetingBefore = greetingSnapshot(greetingId);

        friendshipDeletionService.deleteFriendship(
                sourceUserId,
                sourcePetId,
                targetPetId
        );

        assertThat(roomSnapshot(roomId)).isEqualTo(roomBefore);
        assertThat(participantSnapshots(roomId))
                .containsExactlyElementsOf(participantsBefore);
        assertThat(messageSnapshot(initial.data().messageId()))
                .isEqualTo(messageBefore);
        assertThat(messageCount(roomId)).isEqualTo(messageCountBefore);
        assertThat(friendRequestSnapshot(requestId)).isEqualTo(requestBefore);
        assertThat(greetingSnapshot(greetingId)).isEqualTo(greetingBefore);

        assertThat(chatQueryService.getRooms(sourceUserId, null, 20).items())
                .extracting(room -> room.roomId())
                .contains(roomId);
        assertThat(chatQueryService.getRoom(sourceUserId, roomId).roomId())
                .isEqualTo(roomId);
        ChatQueryService.SendMessageResult sent = chatQueryService.sendMessage(
                sourceUserId,
                roomId,
                new ChatMessageCreateRequest("after-delete", "삭제 후 메시지")
        );

        assertThat(sent.created()).isTrue();
        assertThat(messageCount(roomId)).isEqualTo(messageCountBefore + 1);
        assertThat(messageSnapshot(initial.data().messageId()))
                .isEqualTo(messageBefore);
        assertThat(roomSnapshot(roomId).get("last_message_at"))
                .isNotEqualTo(roomBefore.get("last_message_at"));
        assertThat(roomSnapshot(roomId).get("updated_at"))
                .isNotEqualTo(roomBefore.get("updated_at"));
        assertThat(friendRequestSnapshot(requestId)).isEqualTo(requestBefore);
        assertThat(greetingSnapshot(greetingId)).isEqualTo(greetingBefore);
    }

    private Long createDirectRoom() {
        return chatRoomService.ensureDirectRoom(
                sourcePetId,
                targetPetId,
                RoomOrigin.FRIEND
        ).roomId();
    }

    private Long insertAcceptedFriendRequest() {
        Instant requestedAt = Instant.parse("2026-08-01T00:00:00Z");
        Instant respondedAt = Instant.parse("2026-08-01T01:00:00Z");
        Instant expiresAt = Instant.parse("2026-08-08T00:00:00Z");
        return jdbcTemplate.queryForObject("""
                INSERT INTO friend_requests (
                    requester_pet_id,
                    target_pet_id,
                    status,
                    requested_at,
                    responded_at,
                    expires_at,
                    created_at,
                    updated_at
                ) VALUES (?, ?, 'ACCEPTED', ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                sourcePetId,
                targetPetId,
                requestedAt.atOffset(ZoneOffset.UTC),
                respondedAt.atOffset(ZoneOffset.UTC),
                expiresAt.atOffset(ZoneOffset.UTC),
                requestedAt.atOffset(ZoneOffset.UTC),
                respondedAt.atOffset(ZoneOffset.UTC)
        );
    }

    private Long insertRespondedGreeting(Long roomId) {
        Long mediaId = jdbcTemplate.queryForObject("""
                INSERT INTO media (
                    media_type,
                    path,
                    status,
                    user_id
                ) VALUES ('VIDEO', ?, 'COMPLETED', ?)
                RETURNING id
                """,
                Long.class,
                "friendship-delete/" + unique() + ".mp4",
                sourceUserId
        );
        Long setlogId = jdbcTemplate.queryForObject("""
                INSERT INTO setlogs (
                    author_pet_id,
                    media_id,
                    caption,
                    status
                ) VALUES (?, ?, 'fixture', 'VISIBLE')
                RETURNING id
                """,
                Long.class,
                sourcePetId,
                mediaId
        );
        Instant respondedAt = Instant.parse("2026-08-01T01:00:00Z");
        return jdbcTemplate.queryForObject("""
                INSERT INTO greetings (
                    from_pet_id,
                    to_pet_id,
                    setlog_id,
                    room_id,
                    status,
                    responded_at,
                    expires_at,
                    created_at
                ) VALUES (?, ?, ?, ?, 'RESPONDED', ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                sourcePetId,
                targetPetId,
                setlogId,
                roomId,
                respondedAt.atOffset(ZoneOffset.UTC),
                respondedAt.plusSeconds(3600).atOffset(ZoneOffset.UTC),
                respondedAt.minusSeconds(3600).atOffset(ZoneOffset.UTC)
        );
    }

    private Map<String, Object> roomSnapshot(Long roomId) {
        return jdbcTemplate.queryForMap("""
                SELECT id, status, origin, pet_low_id, pet_high_id,
                       last_message_at, archived_at, created_at, updated_at
                  FROM chat_rooms
                 WHERE id = ?
                """, roomId);
    }

    private java.util.List<Map<String, Object>> participantSnapshots(
            Long roomId
    ) {
        return jdbcTemplate.queryForList("""
                SELECT id, room_id, pet_id, joined_at, left_at
                  FROM chat_room_participants
                 WHERE room_id = ?
                 ORDER BY pet_id
                """, roomId);
    }

    private Map<String, Object> messageSnapshot(Long messageId) {
        return jdbcTemplate.queryForMap("""
                SELECT id, room_id, sender_type, sender_pet_id, type, body,
                       meeting_card_id, client_message_id, created_at
                  FROM chat_messages
                 WHERE id = ?
                """, messageId);
    }

    private Map<String, Object> friendRequestSnapshot(Long requestId) {
        return jdbcTemplate.queryForMap("""
                SELECT id, requester_pet_id, target_pet_id, status,
                       requested_at, responded_at, expires_at,
                       created_at, updated_at
                  FROM friend_requests
                 WHERE id = ?
                """, requestId);
    }

    private Map<String, Object> greetingSnapshot(Long greetingId) {
        return jdbcTemplate.queryForMap("""
                SELECT id, from_pet_id, to_pet_id, setlog_id, room_id,
                       status, responded_at, expires_at, created_at
                  FROM greetings
                 WHERE id = ?
                """, greetingId);
    }

    private int messageCount(Long roomId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chat_messages WHERE room_id = ?",
                Integer.class,
                roomId
        );
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

    private void setActivePet(Long userId, Long petId) {
        jdbcTemplate.update(
                "UPDATE users SET active_pet_id = ? WHERE id = ?",
                petId,
                userId
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

    private String unique() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
