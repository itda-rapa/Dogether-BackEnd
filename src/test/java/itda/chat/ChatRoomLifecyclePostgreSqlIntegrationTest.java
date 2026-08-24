package itda.chat;

import static org.assertj.core.api.Assertions.assertThat;

import itda.chat.domain.RoomOrigin;
import itda.chat.service.ChatRoomLifecycleMaintenanceService;
import itda.chat.service.ChatRoomService;
import java.time.Instant;
import java.sql.Timestamp;
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
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
        "app.chat-room.lifecycle.enabled=false",
        "app.chat-room.lifecycle.batch-size=2"
})
class ChatRoomLifecyclePostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private ChatRoomLifecycleMaintenanceService maintenanceService;

    @Autowired
    private ChatRoomService chatRoomService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("""
                truncate risk_signal_outbox, reports, greetings, friendships, chat_messages,
                         chat_room_participants, chat_rooms, setlogs, media,
                         pets, users
                restart identity cascade
                """);
        insertUser(1L);
        insertUser(2L);
        insertPet(11L, 1L);
        insertPet(22L, 2L);
    }

    @Test
    void expiresAndDeletesUnreportedUnansweredRoomButPreservesGreeting() {
        long roomId = newRoom();
        long greetingId = insertGreeting(roomId, "SENT", NOW.minusSeconds(1));
        insertMessage(roomId, 11L, "first greeting");

        ChatRoomLifecycleMaintenanceService.MaintenanceResult result =
                maintenanceService.runOnce(NOW);

        assertThat(result.expiredGreetings()).isEqualTo(1);
        assertThat(result.deletedRooms()).isEqualTo(1);
        assertThat(statusOfGreeting(greetingId)).isEqualTo("EXPIRED");
        assertThat(count("chat_rooms")).isZero();
        assertThat(count("chat_room_participants")).isZero();
        assertThat(count("chat_messages")).isZero();
    }

    @Test
    void preservesReportedRoomAndEvidence() {
        long roomId = newRoom();
        long greetingId = insertGreeting(roomId, "SENT", NOW.minusSeconds(1));
        insertMessage(roomId, 11L, "evidence");
        insertReport(roomId);

        maintenanceService.runOnce(NOW);

        assertThat(statusOfGreeting(greetingId)).isEqualTo("EXPIRED");
        assertThat(count("chat_rooms")).isEqualTo(1);
        assertThat(count("chat_messages")).isEqualTo(1);
        assertThat(count("reports")).isEqualTo(1);
    }

    @Test
    void preservesRoomWhenAnotherGreetingInTheRoomWasAnswered() {
        long roomId = newRoom();
        insertGreeting(roomId, "RESPONDED", NOW.minusSeconds(2), 11L, 22L);
        long expiredGreetingId = insertGreeting(
                roomId, "SENT", NOW.minusSeconds(1), 22L, 11L);

        maintenanceService.runOnce(NOW);

        assertThat(statusOfGreeting(expiredGreetingId)).isEqualTo("EXPIRED");
        assertThat(count("chat_rooms")).isEqualTo(1);
    }

    @Test
    void preservesFriendRoomWhenUnansweredGreetingExpires() {
        long roomId = newRoom();
        long greetingId = insertGreeting(roomId, "SENT", NOW.minusSeconds(1));
        insertMessage(roomId, 11L, "first greeting");
        jdbcTemplate.update("""
                insert into friendships (pet_low_id, pet_high_id)
                values (11, 22)
                """);

        ChatRoomLifecycleMaintenanceService.MaintenanceResult result =
                maintenanceService.runOnce(NOW);

        assertThat(result.expiredGreetings()).isEqualTo(1);
        assertThat(result.deletedRooms()).isZero();
        assertThat(statusOfGreeting(greetingId)).isEqualTo("EXPIRED");
        assertThat(count("chat_rooms")).isEqualTo(1);
        assertThat(count("chat_room_participants")).isEqualTo(2);
        assertThat(count("chat_messages")).isEqualTo(1);
    }

    @Test
    void deletesRoomDependentCardRowsBeforeDeletingRoom() {
        long roomId = newRoom();
        insertGreeting(roomId, "SENT", NOW.minusSeconds(1));
        long draftId = jdbcTemplate.queryForObject("""
                insert into card_drafts (room_id, requested_by_pet_id)
                values (?, 11)
                returning id
                """, Long.class, roomId);
        long cardId = jdbcTemplate.queryForObject("""
                insert into meeting_cards (
                    room_id, creator_pet_id, source_draft_id,
                    card_type, place_text, meet_at
                ) values (?, 11, ?, 'WALK', '공원', ?)
                returning id
                """, Long.class, roomId, draftId,
                Timestamp.from(NOW.plusSeconds(3600)));
        jdbcTemplate.update("""
                insert into meeting_participants (meeting_card_id, pet_id)
                values (?, 11), (?, 22)
                """, cardId, cardId);
        jdbcTemplate.update("""
                insert into chat_messages (
                    room_id, sender_type, sender_pet_id,
                    type, meeting_card_id, client_message_id
                ) values (?, 'PET', 11, 'CARD', ?, ?)
                """, roomId, cardId, "lifecycle-card-" + cardId);

        maintenanceService.runOnce(NOW);

        assertThat(count("card_drafts")).isZero();
        assertThat(count("meeting_cards")).isZero();
        assertThat(count("meeting_participants")).isZero();
        assertThat(count("chat_rooms")).isZero();
    }

    @Test
    void expiresOrphanGreetingEvenWhenRoomIsAlreadyGone() {
        long roomId = newRoom();
        long greetingId = insertGreeting(roomId, "SENT", NOW.minusSeconds(1));
        jdbcTemplate.update("delete from chat_rooms where id = ?", roomId);

        maintenanceService.runOnce(NOW);

        assertThat(statusOfGreeting(greetingId)).isEqualTo("EXPIRED");
    }

    @Test
    void archivesOldAnsweredNonFriendRoom() {
        long roomId = newRoom();
        insertGreeting(roomId, "RESPONDED", NOW.minusSeconds(31L * 24 * 60 * 60));
        setLastMessageAt(roomId, NOW.minusSeconds(31L * 24 * 60 * 60));

        ChatRoomLifecycleMaintenanceService.MaintenanceResult result =
                maintenanceService.runOnce(NOW);

        assertThat(result.archivedRooms()).isEqualTo(1);
        assertThat(statusOfRoom(roomId)).isEqualTo("ARCHIVED");
    }

    @Test
    void doesNotArchiveOldAnsweredFriendRoom() {
        long roomId = newRoom();
        insertGreeting(roomId, "RESPONDED", NOW.minusSeconds(31L * 24 * 60 * 60));
        setLastMessageAt(roomId, NOW.minusSeconds(31L * 24 * 60 * 60));
        jdbcTemplate.update("""
                insert into friendships (pet_low_id, pet_high_id)
                values (11, 22)
                """);

        ChatRoomLifecycleMaintenanceService.MaintenanceResult result =
                maintenanceService.runOnce(NOW);

        assertThat(result.archivedRooms()).isZero();
        assertThat(statusOfRoom(roomId)).isEqualTo("ACTIVE");
    }

    @Test
    void archivesNonFriendRoomEvenWhenOldFriendRoomsFillBatch() {
        insertUser(3L);
        insertUser(4L);
        insertUser(5L);
        insertUser(6L);
        insertPet(33L, 3L);
        insertPet(44L, 4L);
        insertPet(55L, 5L);
        insertPet(66L, 6L);

        long firstFriendRoomId = chatRoomService
                .ensureDirectRoom(11L, 22L, RoomOrigin.GREETING)
                .roomId();
        long secondFriendRoomId = chatRoomService
                .ensureDirectRoom(33L, 44L, RoomOrigin.GREETING)
                .roomId();
        long nonFriendRoomId = chatRoomService
                .ensureDirectRoom(55L, 66L, RoomOrigin.GREETING)
                .roomId();

        Instant fortyDaysAgo = NOW.minusSeconds(40L * 24 * 60 * 60);
        Instant thirtyNineDaysAgo = NOW.minusSeconds(39L * 24 * 60 * 60);
        Instant thirtyOneDaysAgo = NOW.minusSeconds(31L * 24 * 60 * 60);
        insertGreeting(firstFriendRoomId, "RESPONDED", fortyDaysAgo, 11L, 22L);
        insertGreeting(secondFriendRoomId, "RESPONDED", thirtyNineDaysAgo, 33L, 44L);
        insertGreeting(nonFriendRoomId, "RESPONDED", thirtyOneDaysAgo, 55L, 66L);
        setLastMessageAt(firstFriendRoomId, fortyDaysAgo);
        setLastMessageAt(secondFriendRoomId, thirtyNineDaysAgo);
        setLastMessageAt(nonFriendRoomId, thirtyOneDaysAgo);
        jdbcTemplate.update("""
                insert into friendships (pet_low_id, pet_high_id)
                values (11, 22), (33, 44)
                """);

        ChatRoomLifecycleMaintenanceService.MaintenanceResult result =
                maintenanceService.runOnce(NOW);

        assertThat(result.archivedRooms()).isEqualTo(1);
        assertThat(statusOfRoom(firstFriendRoomId)).isEqualTo("ACTIVE");
        assertThat(statusOfRoom(secondFriendRoomId)).isEqualTo("ACTIVE");
        assertThat(statusOfRoom(nonFriendRoomId)).isEqualTo("ARCHIVED");
    }

    @Test
    void repeatedMaintenanceIsIdempotent() {
        long roomId = newRoom();
        long greetingId = insertGreeting(roomId, "SENT", NOW.minusSeconds(1));
        insertMessage(roomId, 11L, "first greeting");

        maintenanceService.runOnce(NOW);
        ChatRoomLifecycleMaintenanceService.MaintenanceResult second =
                maintenanceService.runOnce(NOW);

        assertThat(second.expiredGreetings()).isZero();
        assertThat(second.deletedRooms()).isZero();
        assertThat(statusOfGreeting(greetingId)).isEqualTo("EXPIRED");
    }

    @Test
    void expiresGreetingAndStoresOneRiskEventWithExpiresAt() {
        long roomId = newRoom();
        long greetingId = insertGreeting(roomId, "SENT", NOW.minusSeconds(1));

        maintenanceService.runOnce(NOW);

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from risk_signal_outbox
                 where source_type = 'GREETING' and source_id = ?
                   and signal_type = 'GREETING_EXPIRED'
                """, Long.class, greetingId)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                select occurred_at from risk_signal_outbox
                 where source_type = 'GREETING' and source_id = ?
                """, Timestamp.class, greetingId).toInstant()).isEqualTo(NOW.minusSeconds(1));

        maintenanceService.runOnce(NOW);

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from risk_signal_outbox
                 where source_type = 'GREETING' and source_id = ?
                """, Long.class, greetingId)).isEqualTo(1L);
    }

    @Test
    void riskOutboxFailureRollsBackGreetingExpiration() {
        long roomId = newRoom();
        long greetingId = insertGreeting(roomId, "SENT", NOW.minusSeconds(1));
        jdbcTemplate.execute("""
                create or replace function reject_greeting_risk_event()
                returns trigger language plpgsql as $$
                begin
                  raise exception 'forced risk outbox failure';
                end $$
                """);
        jdbcTemplate.execute("""
                create trigger reject_greeting_risk_event
                before insert on risk_signal_outbox
                for each row execute function reject_greeting_risk_event()
                """);

        try {
            ChatRoomLifecycleMaintenanceService.MaintenanceResult result =
                    maintenanceService.runOnce(NOW);

            assertThat(result.expiredGreetings()).isZero();
            assertThat(statusOfGreeting(greetingId)).isEqualTo("SENT");
            assertThat(count("risk_signal_outbox")).isZero();
        } finally {
            jdbcTemplate.execute("drop trigger reject_greeting_risk_event on risk_signal_outbox");
            jdbcTemplate.execute("drop function reject_greeting_risk_event()");
        }
    }

    private long newRoom() {
        return chatRoomService.ensureDirectRoom(11L, 22L, RoomOrigin.GREETING).roomId();
    }

    private long insertGreeting(long roomId, String status, Instant expiresAt) {
        return insertGreeting(roomId, status, expiresAt, 11L, 22L);
    }

    private long insertGreeting(
            long roomId,
            String status,
            Instant expiresAt,
            long fromPetId,
            long toPetId
    ) {
        long mediaId = jdbcTemplate.queryForObject("""
                insert into media (media_type, path, status, user_id, file_size)
                values ('VIDEO', ?, 'UPLOADED', 1, 100)
                returning id
                """, Long.class, "lifecycle-" + roomId + "-" + status);
        long setlogId = jdbcTemplate.queryForObject("""
                insert into setlogs (author_pet_id, media_id, status, is_seed)
                values (?, ?, 'VISIBLE', true)
                returning id
                """, Long.class, toPetId, mediaId);
        return jdbcTemplate.queryForObject("""
                insert into greetings (
                    from_pet_id, to_pet_id, setlog_id, room_id,
                    status, responded_at, expires_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, fromPetId, toPetId, setlogId, roomId, status,
                "RESPONDED".equals(status) ? Timestamp.from(expiresAt) : null,
                Timestamp.from(expiresAt));
    }

    private void insertMessage(long roomId, long senderPetId, String body) {
        jdbcTemplate.update("""
                insert into chat_messages (
                    room_id, sender_type, sender_pet_id, type, body, client_message_id
                ) values (?, 'PET', ?, 'TEXT', ?, ?)
                """, roomId, senderPetId, body, "lifecycle-message-" + roomId);
    }

    private void insertReport(long roomId) {
        jdbcTemplate.update("""
                insert into reports (
                    reporter_user_id, reporter_pet_id,
                    reported_user_id, reported_pet_id,
                    room_id, reason_code
                ) values (1, 11, 2, 22, ?, 'SPAM')
                """, roomId);
    }

    private void setLastMessageAt(long roomId, Instant lastMessageAt) {
        jdbcTemplate.update(
                "update chat_rooms set last_message_at = ? where id = ?",
                Timestamp.from(lastMessageAt),
                roomId
        );
    }

    private void insertUser(long userId) {
        jdbcTemplate.update("""
                insert into users (
                    id, email, password_hash, nickname, public_tag,
                    role, account_status, neighborhood_code
                ) values (?, ?, 'encoded', ?, ?, 'USER', 'ACTIVE', '4113111500')
                """, userId, "lifecycle" + userId + "@test.com",
                "사용자" + userId, "user" + userId + "#ABCD");
    }

    private void insertPet(long petId, long ownerUserId) {
        jdbcTemplate.update("""
                insert into pets (id, owner_user_id, public_tag, nickname, status)
                values (?, ?, ?, ?, 'ACTIVE')
                """, petId, ownerUserId,
                "pet" + petId + "#ABCD", "펫" + petId);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }

    private String statusOfGreeting(long greetingId) {
        return jdbcTemplate.queryForObject(
                "select status from greetings where id = ?",
                String.class,
                greetingId
        );
    }

    private String statusOfRoom(long roomId) {
        return jdbcTemplate.queryForObject(
                "select status from chat_rooms where id = ?",
                String.class,
                roomId
        );
    }
}
