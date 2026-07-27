package itda.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.chat.domain.ChatMessage;
import itda.chat.domain.MessageType;
import itda.chat.domain.RoomOrigin;
import itda.chat.domain.SenderType;
import itda.chat.dto.EnsureDirectRoomResult;
import itda.chat.dto.SendTextRequest;
import itda.chat.service.ChatMessageService;
import itda.chat.service.ChatRoomService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.time.Instant;
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

/**
 * Drives ChatRoomService and ChatMessageService against a real PostgreSQL instance.
 *
 * <p>The H2 unit suite mocks both repositories, so until this test existed none of the native
 * queries had ever executed — in particular {@code insertMessageOnConflictWithReturning}, whose
 * CTE-with-RETURNING shape the whole idempotency contract depends on.
 */
@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class ChatServicePostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private ChatRoomService chatRoomService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetChatTables() {
        jdbcTemplate.execute(
                "truncate chat_messages, chat_room_participants, chat_rooms restart identity cascade");
    }

    // ---------- ChatRoomService.ensureDirectRoom ----------

    @Test
    void createsRoomWithBothParticipants() {
        EnsureDirectRoomResult result = chatRoomService.ensureDirectRoom(11L, 22L, RoomOrigin.GREETING);

        assertThat(result.isNew()).isTrue();
        assertThat(participantPetIdsOf(result.roomId())).containsExactlyInAnyOrder(11L, 22L);
        assertThat(jdbcTemplate.queryForObject(
                "select pet_low_id from chat_rooms where id = ?", Long.class, result.roomId()))
                .isEqualTo(11L);
    }

    @Test
    void reversedPetOrderResolvesToTheSameRoom() {
        EnsureDirectRoomResult first = chatRoomService.ensureDirectRoom(11L, 22L, RoomOrigin.GREETING);
        EnsureDirectRoomResult second = chatRoomService.ensureDirectRoom(22L, 11L, RoomOrigin.FRIEND);

        assertThat(second.roomId()).isEqualTo(first.roomId());
        assertThat(second.isNew()).isFalse();
        assertThat(countOf("chat_rooms")).isEqualTo(1);
        // The second call must not add a duplicate participant pair either.
        assertThat(countOf("chat_room_participants")).isEqualTo(2);
    }

    @Test
    void rejectsARoomAPetWouldShareWithItself() {
        assertThatThrownBy(() -> chatRoomService.ensureDirectRoom(11L, 11L, RoomOrigin.GREETING))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_SAME_PET_FORBIDDEN);
    }

    // ---------- ChatMessageService ----------

    @Test
    void sendTextPersistsTheMessageAndReturnsTheInsertedRow() {
        long roomId = newRoom(11L, 22L);

        ChatMessage message = chatMessageService.sendText(
                new SendTextRequest(roomId, 11L, "안녕하세요", "idem-1"));

        // The service reads its result back by the id the native INSERT returned, so a wrong
        // id here would mean insertMessageOnConflictWithReturning handed back a row count.
        assertThat(message.getId()).isNotNull();
        assertThat(message.getBody()).isEqualTo("안녕하세요");
        assertThat(message.getType()).isEqualTo(MessageType.TEXT);
        assertThat(message.getSenderType()).isEqualTo(SenderType.PET);
        assertThat(message.getSenderPetId()).isEqualTo(11L);
        assertThat(countOf("chat_messages")).isEqualTo(1);
    }

    @Test
    void resendingTheSameKeyReturnsTheOriginalRow() {
        long roomId = newRoom(11L, 22L);
        SendTextRequest request = new SendTextRequest(roomId, 11L, "안녕하세요", "idem-1");

        ChatMessage first = chatMessageService.sendText(request);
        ChatMessage retry = chatMessageService.sendText(request);

        assertThat(retry.getId()).isEqualTo(first.getId());
        assertThat(countOf("chat_messages")).isEqualTo(1);
    }

    @Test
    void reusingAKeyWithDifferentContentIsRejected() {
        long roomId = newRoom(11L, 22L);
        chatMessageService.sendText(new SendTextRequest(roomId, 11L, "원본", "idem-1"));

        assertThatThrownBy(() -> chatMessageService.sendText(
                new SendTextRequest(roomId, 11L, "다른 내용", "idem-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_DUPLICATE_MESSAGE);
    }

    @Test
    void sendingAdvancesRoomActivityButResendingDoesNot() {
        long roomId = newRoom(11L, 22L);
        assertThat(lastMessageAtOf(roomId)).isNull();

        SendTextRequest request = new SendTextRequest(roomId, 11L, "안녕하세요", "idem-1");
        chatMessageService.sendText(request);
        Instant afterFirstSend = lastMessageAtOf(roomId);
        assertThat(afterFirstSend).isNotNull();

        chatMessageService.sendText(request);

        // A retry is not new activity, so the room timestamp must be untouched.
        assertThat(lastMessageAtOf(roomId)).isEqualTo(afterFirstSend);
    }

    @Test
    void systemNoticesShareARoomWithoutAnIdempotencyKey() {
        long roomId = newRoom(11L, 22L);

        ChatMessage first = chatMessageService.postSystem(roomId, "notice 1", null);
        ChatMessage second = chatMessageService.postSystem(roomId, "notice 2", null);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(first.getSenderType()).isEqualTo(SenderType.SYSTEM);
        assertThat(first.getSenderPetId()).isNull();
        assertThat(countOf("chat_messages")).isEqualTo(2);
    }

    @Test
    void cardAnnouncementIsStoredAsACardMessage() {
        long roomId = newRoom(11L, 22L);

        ChatMessage card = chatMessageService.postCard(roomId, 11L, 77L, "card-77");

        assertThat(card.getType()).isEqualTo(MessageType.CARD);
        assertThat(card.getMeetingCardId()).isEqualTo(77L);
        assertThat(card.getSenderPetId()).isEqualTo(11L);
    }

    @Test
    void sendingToAMissingRoomIsRejected() {
        assertThatThrownBy(() -> chatMessageService.sendText(
                new SendTextRequest(9999L, 11L, "안녕하세요", "idem-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
    }

    // ---------- helpers ----------

    private long newRoom(long petAId, long petBId) {
        return chatRoomService.ensureDirectRoom(petAId, petBId, RoomOrigin.GREETING).roomId();
    }

    private java.util.List<Long> participantPetIdsOf(long roomId) {
        return jdbcTemplate.queryForList(
                "select pet_id from chat_room_participants where room_id = ?", Long.class, roomId);
    }

    private Instant lastMessageAtOf(long roomId) {
        return jdbcTemplate.queryForObject(
                "select last_message_at from chat_rooms where id = ?", Instant.class, roomId);
    }

    private int countOf(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }
}
