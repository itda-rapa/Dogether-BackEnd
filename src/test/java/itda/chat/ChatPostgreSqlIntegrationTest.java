package itda.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Checks the V7 chat constraints against a real PostgreSQL instance. The H2 unit suite cannot
 * cover these: it builds its schema from the entities rather than the migration, and it does not
 * implement partial unique indexes at all.
 *
 * <p>Each test truncates the chat tables first and reads generated ids back via {@code RETURNING},
 * so no test depends on another test's rows or on a particular identity value.
 */
@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class ChatPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer(
                DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                        .asCompatibleSubstituteFor("postgres")
        );

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetChatTables() {
        jdbcTemplate.execute(
                "truncate chat_messages, chat_room_participants, chat_rooms restart identity cascade");
    }

    @Test
    void migrationsApplyAndChatTablesAreEmpty() {
        assertThat(flyway.info().pending()).isEmpty();

        assertThat(countOf("chat_rooms")).isZero();
        assertThat(countOf("chat_room_participants")).isZero();
        assertThat(countOf("chat_messages")).isZero();
    }

    // ---------- chat_rooms ----------

    @Test
    void rejectsDirectRoomWithTheSamePetOnBothSides() {
        // ck_chat_room_direct_pair requires pet_low_id < pet_high_id, which also rules out a
        // room a pet would share with itself.
        assertThatThrownBy(() -> insertRoom(4L, 4L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_chat_room_direct_pair");
    }

    @Test
    void rejectsUnnormalizedDirectPair() {
        assertThatThrownBy(() -> insertRoom(9L, 3L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_chat_room_direct_pair");
    }

    @Test
    void rejectsDuplicateDirectPair() {
        insertRoom(1L, 2L);

        assertThatThrownBy(() -> insertRoom(1L, 2L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_chat_room_direct_pair");
    }

    @Test
    void onConflictKeepsExactlyOneRoomForAPair() {
        insertRoom(7L, 8L);

        // Mirrors ChatRoomRepository.insertDirectRoomOnConflict: the conflict target must match
        // the partial index (pet_low_id, pet_high_id) WHERE type = 'DIRECT'.
        int inserted = jdbcTemplate.update("""
                insert into chat_rooms (type, status, origin, pet_low_id, pet_high_id)
                values ('DIRECT', 'ACTIVE', 'GREETING', 7, 8)
                on conflict (pet_low_id, pet_high_id) where type = 'DIRECT'
                do nothing
                """);

        assertThat(inserted).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from chat_rooms where pet_low_id = 7 and pet_high_id = 8",
                Integer.class)).isEqualTo(1);
    }

    // ---------- chat_room_participants ----------

    @Test
    void rejectsDuplicateParticipant() {
        long roomId = insertRoom(1L, 2L);
        insertParticipant(roomId, 1L);
        insertParticipant(roomId, 2L);

        assertThatThrownBy(() -> insertParticipant(roomId, 1L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_chat_participant");
    }

    @Test
    void rejectsParticipantInAMissingRoom() {
        long roomId = insertRoom(1L, 2L);

        assertThatThrownBy(() -> insertParticipant(roomId + 999, 1L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chat_room_participants_room_id_fkey");
    }

    // ---------- chat_messages ----------

    @Test
    void rejectsPetSenderWithoutPetId() {
        long roomId = insertRoom(3L, 4L);

        assertThatThrownBy(() -> insertMessage(roomId, "PET", null, "TEXT", "test", "idem-a"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_chat_message_sender");
    }

    @Test
    void rejectsSystemSenderCarryingAPetId() {
        long roomId = insertRoom(5L, 6L);

        assertThatThrownBy(() -> insertMessage(roomId, "SYSTEM", 5L, "SYSTEM", "test", "idem-b"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_chat_message_sender");
    }

    @Test
    void rejectsTextWithoutBody() {
        long roomId = insertRoom(5L, 6L);

        assertThatThrownBy(() -> insertMessage(roomId, "PET", 5L, "TEXT", null, "idem-empty"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_chat_message_payload");
    }

    @Test
    void rejectsReusedClientMessageIdInTheSameRoom() {
        long roomId = insertRoom(7L, 8L);
        insertMessage(roomId, "PET", 7L, "TEXT", "hello", "idem-x");

        assertThatThrownBy(() -> insertMessage(roomId, "PET", 7L, "TEXT", "another body", "idem-x"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_chat_message_client");
    }

    @Test
    void allowsTheSameClientMessageIdInDifferentRooms() {
        // The idempotency key is scoped per room, so two rooms may legitimately share one.
        long roomA = insertRoom(1L, 2L);
        long roomB = insertRoom(3L, 4L);

        insertMessage(roomA, "PET", 1L, "TEXT", "hello", "shared-key");

        assertThatCode(() -> insertMessage(roomB, "PET", 3L, "TEXT", "hello", "shared-key"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsMultipleNullClientMessageIds() {
        // PostgreSQL treats NULLs as distinct inside a UNIQUE constraint, which is what lets
        // server-authored SYSTEM notices omit an idempotency key.
        long roomId = insertRoom(9L, 10L);

        insertMessage(roomId, "SYSTEM", null, "SYSTEM", "notice 1", null);
        insertMessage(roomId, "SYSTEM", null, "SYSTEM", "notice 2", null);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from chat_messages where room_id = ?", Integer.class, roomId))
                .isEqualTo(2);
    }

    // ---------- helpers ----------

    private long insertRoom(long petLowId, long petHighId) {
        return jdbcTemplate.queryForObject("""
                insert into chat_rooms (type, status, origin, pet_low_id, pet_high_id)
                values ('DIRECT', 'ACTIVE', 'GREETING', ?, ?)
                returning id
                """, Long.class, petLowId, petHighId);
    }

    private void insertParticipant(long roomId, long petId) {
        jdbcTemplate.update(
                "insert into chat_room_participants (room_id, pet_id) values (?, ?)", roomId, petId);
    }

    private void insertMessage(long roomId, String senderType, Long senderPetId,
                               String type, String body, String clientMessageId) {
        jdbcTemplate.update("""
                insert into chat_messages (room_id, sender_type, sender_pet_id, type, body, client_message_id)
                values (?, ?, ?, ?, ?, ?)
                """, roomId, senderType, senderPetId, type, body, clientMessageId);
    }

    private int countOf(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }
}
