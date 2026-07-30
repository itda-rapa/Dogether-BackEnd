package itda.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.chat.domain.MessageType;
import itda.chat.domain.RoomOrigin;
import itda.chat.domain.SenderType;
import itda.chat.dto.ChatMessageCreateRequest;
import itda.chat.dto.ChatMessageResult;
import itda.chat.dto.EnsureDirectRoomResult;
import itda.chat.service.ChatMessageService;
import itda.chat.service.ChatRoomService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.time.Instant;
import java.util.List;
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
 * upsert-with-RETURNING shape the whole idempotency contract depends on.
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
        jdbcTemplate.execute("""
                truncate meeting_participants, meeting_cards, card_drafts,
                         chat_messages, chat_room_participants, chat_rooms,
                         pets, users
                restart identity cascade
                """);
    }

    /**
     * Creates a real meeting_cards row and returns its id.
     *
     * <p>V14 added the {@code chat_messages.meeting_card_id -> meeting_cards.id} foreign key that
     * the canonical ERD always specified but V7 could not install, because meeting_cards did not
     * exist yet. A CARD message can no longer point at an invented card id, so the owning row and
     * its User/Pet prerequisites have to exist first.
     */
    private long newMeetingCard(long roomId, long creatorPetId) {
        jdbcTemplate.update("""
                insert into users (id, email, password_hash, nickname, public_tag,
                                   role, account_status, neighborhood_code)
                values (?, ?, 'encoded', ?, ?, 'USER', 'ACTIVE', '4113111500')
                """, creatorPetId, "owner" + creatorPetId + "@test.com",
                "보호자" + creatorPetId, "owner" + creatorPetId + "#0001");
        jdbcTemplate.update("""
                insert into pets (id, owner_user_id, public_tag, nickname, status)
                values (?, ?, ?, ?, 'ACTIVE')
                """, creatorPetId, creatorPetId,
                "pet" + creatorPetId + "#0001", "펫" + creatorPetId);
        jdbcTemplate.update("""
                insert into meeting_cards (room_id, creator_pet_id, card_type, place_text, meet_at)
                values (?, ?, 'WALK', '중앙공원', now())
                """, roomId, creatorPetId);
        return jdbcTemplate.queryForObject(
                "select id from meeting_cards where room_id = ?", Long.class, roomId);
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
    void sendTextPersistsTheMessageAndReportsItAsCreated() {
        long roomId = newRoom(11L, 22L);

        ChatMessageResult result = chatMessageService.sendText(roomId, 11L, text("idem-1", "안녕하세요"));

        // The service reads its result back by the id the native INSERT returned, so a wrong id
        // here would mean insertMessageOnConflictWithReturning handed back something else.
        assertThat(result.created()).isTrue();
        assertThat(result.message().getId()).isNotNull();
        assertThat(result.message().getBody()).isEqualTo("안녕하세요");
        assertThat(result.message().getType()).isEqualTo(MessageType.TEXT);
        assertThat(result.message().getSenderType()).isEqualTo(SenderType.PET);
        assertThat(result.message().getSenderPetId()).isEqualTo(11L);
        assertThat(countOf("chat_messages")).isEqualTo(1);
    }

    @Test
    void resendingTheSameKeyReturnsTheOriginalRowAsNotCreated() {
        long roomId = newRoom(11L, 22L);
        ChatMessageCreateRequest request = text("idem-1", "안녕하세요");

        ChatMessageResult first = chatMessageService.sendText(roomId, 11L, request);
        ChatMessageResult retry = chatMessageService.sendText(roomId, 11L, request);

        assertThat(first.created()).isTrue();
        assertThat(retry.created()).isFalse();
        assertThat(retry.message().getId()).isEqualTo(first.message().getId());
        assertThat(countOf("chat_messages")).isEqualTo(1);
    }

    @Test
    void reusingAKeyWithDifferentContentIsRejected() {
        long roomId = newRoom(11L, 22L);
        chatMessageService.sendText(roomId, 11L, text("idem-1", "원본"));

        assertThatThrownBy(() -> chatMessageService.sendText(roomId, 11L, text("idem-1", "다른 내용")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_DUPLICATE_MESSAGE);
    }

    @Test
    void nonParticipantCannotSendToTheRoom() {
        long roomId = newRoom(11L, 22L);

        assertThatThrownBy(() -> chatMessageService.sendText(roomId, 99L, text("idem-x", "끼어들기")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_SENDER_NOT_PARTICIPANT);

        assertThat(countOf("chat_messages")).isZero();
    }

    @Test
    void sendingAdvancesRoomActivityButResendingDoesNot() {
        long roomId = newRoom(11L, 22L);
        assertThat(lastMessageAtOf(roomId)).isNull();

        ChatMessageCreateRequest request = text("idem-1", "안녕하세요");
        chatMessageService.sendText(roomId, 11L, request);
        Instant afterFirstSend = lastMessageAtOf(roomId);
        assertThat(afterFirstSend).isNotNull();

        chatMessageService.sendText(roomId, 11L, request);

        // A retry is not new activity, so the room timestamp must be untouched.
        assertThat(lastMessageAtOf(roomId)).isEqualTo(afterFirstSend);
    }

    @Test
    void sendingRestoresAnArchivedRoom() {
        long roomId = newRoom(11L, 22L);
        jdbcTemplate.update(
                "update chat_rooms set status = 'ARCHIVED', archived_at = now() where id = ?",
                roomId);

        chatMessageService.sendText(roomId, 11L, text("idem-restore", "다시 대화해요"));

        assertThat(jdbcTemplate.queryForObject(
                "select status from chat_rooms where id = ?", String.class, roomId))
                .isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "select archived_at from chat_rooms where id = ?", Instant.class, roomId))
                .isNull();
    }

    @Test
    void systemNoticesShareARoomWithoutAnIdempotencyKey() {
        long roomId = newRoom(11L, 22L);

        ChatMessageResult first = chatMessageService.postSystem(roomId, "notice 1", null);
        ChatMessageResult second = chatMessageService.postSystem(roomId, "notice 2", null);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isTrue();
        assertThat(second.message().getId()).isNotEqualTo(first.message().getId());
        assertThat(first.message().getSenderType()).isEqualTo(SenderType.SYSTEM);
        assertThat(first.message().getSenderPetId()).isNull();
        assertThat(countOf("chat_messages")).isEqualTo(2);
    }

    @Test
    void cardAnnouncementIsStoredAsACardMessage() {
        long roomId = newRoom(11L, 22L);
        long cardId = newMeetingCard(roomId, 11L);

        ChatMessageResult card = chatMessageService.postCard(roomId, 11L, cardId, "card-77");

        assertThat(card.created()).isTrue();
        assertThat(card.message().getType()).isEqualTo(MessageType.CARD);
        assertThat(card.message().getMeetingCardId()).isEqualTo(cardId);
        assertThat(card.message().getSenderPetId()).isEqualTo(11L);
    }

    @Test
    void sendingToAMissingRoomIsRejected() {
        assertThatThrownBy(() -> chatMessageService.sendText(9999L, 11L, text("idem-1", "안녕하세요")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
    }

    // ---------- helpers ----------

    private static ChatMessageCreateRequest text(String clientMessageId, String body) {
        return new ChatMessageCreateRequest(clientMessageId, body);
    }

    private long newRoom(long petAId, long petBId) {
        return chatRoomService.ensureDirectRoom(petAId, petBId, RoomOrigin.GREETING).roomId();
    }

    private List<Long> participantPetIdsOf(long roomId) {
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
