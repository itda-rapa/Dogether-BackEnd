package itda.chat;

import static org.assertj.core.api.Assertions.assertThat;

import itda.chat.domain.RoomOrigin;
import itda.chat.dto.ChatMessageCreateRequest;
import itda.chat.dto.response.ChatMessageResponse;
import itda.chat.dto.response.ChatRoomResponse;
import itda.chat.service.ChatMessageService;
import itda.chat.service.ChatQueryService;
import itda.chat.service.ChatRoomService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
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
 * Integration tests for the M1-015 chat REST polling API — all four endpoints
 * driven through {@link ChatQueryService} (read-side) and
 * {@link ChatMessageService} (write-side) against a real PostgreSQL 16
 * instance, exercising LATERAL, cursor predicate, ordering, and limit + 1.
 */
@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
        // enables the statement counter the N+1 guard reads
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class ChatRestPollingApiPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private ChatRoomService chatRoomService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private ChatQueryService chatQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private org.hibernate.stat.Statistics statistics() {
        return entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
    }

    /** Statements issued by one {@code getRooms} call, counted from a clean slate. */
    private long statementsForRoomList(int limit) {
        org.hibernate.stat.Statistics stats = statistics();
        stats.clear();
        chatQueryService.getRooms(USER_1, null, limit);
        return stats.getPrepareStatementCount();
    }

    // Nothing is seeded: db/seed holds neighborhoods only. Users and pets are built here.
    private static final long USER_1 = 1L;
    private static final long USER_2 = 2L;
    private static final long USER_3 = 3L;

    private static final long PET_1 = 11L;
    private static final long PET_2 = 22L;
    private static final long PET_3 = 33L;

    /** The only neighborhood code the seed guarantees. */
    private static final String NEIGHBORHOOD = "4113111500";

    @BeforeEach
    void resetFixture() {
        jdbcTemplate.execute("""
                truncate users, pets, chat_messages, chat_room_participants, chat_rooms
                restart identity cascade
                """);

        insertUser(USER_1);
        insertUser(USER_2);
        insertUser(USER_3);

        // Every pet id any test mentions must exist as a row: the room list resolves counterpart
        // display data through PetDisplayQueryService, which throws PET_NOT_FOUND when an id is
        // missing. Ids are explicit because the tests address pets by number.
        insertPet(PET_1, USER_1);
        insertPet(PET_2, USER_2);
        insertPet(PET_3, USER_3);
        insertPet(44L, USER_3);
        for (long petId = 20L; petId <= 24L; petId++) {
            if (petId != PET_2) {
                insertPet(petId, USER_3);
            }
        }
        for (long petId = 50L; petId <= 74L; petId++) {
            insertPet(petId, USER_3);
        }

        setActivePet(USER_1, PET_1);
        setActivePet(USER_2, PET_2);
        setActivePet(USER_3, PET_3);
    }

    private void insertUser(long userId) {
        jdbcTemplate.update("""
                        insert into users (
                            id, email, password_hash, nickname, public_tag,
                            role, account_status, neighborhood_code
                        ) values (?, ?, 'encoded', ?, ?, 'USER', 'ACTIVE', ?)
                        """,
                userId,
                "user" + userId + "@test.com",
                "사용자" + userId,
                "user" + userId + "#" + String.format("%04d", userId),
                NEIGHBORHOOD);
    }

    /** public_tag must match {@code ^.{1,25}#[A-Z0-9]{4}$} — the four digits satisfy it. */
    private void insertPet(long petId, long ownerUserId) {
        jdbcTemplate.update("""
                        insert into pets (id, owner_user_id, public_tag, nickname, status)
                        values (?, ?, ?, ?, 'ACTIVE')
                        """,
                petId,
                ownerUserId,
                "pet" + petId + "#" + String.format("%04d", petId),
                "펫" + petId);
    }

    private void setActivePet(long userId, long petId) {
        jdbcTemplate.update("update users set active_pet_id = ? where id = ?", petId, userId);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private long newRoom(long petAId, long petBId) {
        return chatRoomService.ensureDirectRoom(petAId, petBId, RoomOrigin.GREETING).roomId();
    }

    private void sendText(long roomId, long senderPetId, String clientMessageId, String body) {
        chatMessageService.sendText(roomId, senderPetId,
                new ChatMessageCreateRequest(clientMessageId, body));
    }

    private int countOf(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }

    // ── GET /chat/rooms ──────────────────────────────────────────────────────

    @Test
    void onlyRoomsWhereActivePetIsParticipant() {
        // Room A: pet 1 ↔ pet 2
        // Room B: pet 3 ↔ pet 4 — should be invisible to pet 1
        long roomA = newRoom(11L, 22L);
        long roomB = newRoom(33L, 44L);

        sendText(roomA, 11L, "m1", "hello from pet 1");

        ChatQueryService.ChatRoomListResult result =
                chatQueryService.getRooms(USER_1, null, 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).roomId()).isEqualTo(roomA);
    }

    @Test
    void orderingByActivityAtDescThenRoomIdDesc() {
        long roomA = newRoom(11L, 22L); // no messages — sorts by created_at
        long roomB = newRoom(11L, 33L); // will have a message — sorts by last_message_at

        sendText(roomB, 11L, "mb", "message in B");

        ChatQueryService.ChatRoomListResult result =
                chatQueryService.getRooms(USER_1, null, 20);

        assertThat(result.items()).hasSize(2);
        // B has a message, so last_message_at > created_at of A
        assertThat(result.items().get(0).roomId()).isEqualTo(roomB);
        assertThat(result.items().get(1).roomId()).isEqualTo(roomA);
    }

    @Test
    void twoRoomsSharingActivityAtOrderedByRoomIdDesc() {
        // Create two rooms and ensure they share the same activityAt (both null = created_at)
        long roomLow = newRoom(11L, 22L);
        long roomHigh = newRoom(11L, 33L);

        // Manually equalise last_message_at and updated_at to force tie-breaker
        jdbcTemplate.update(
                "update chat_rooms set last_message_at = null, updated_at = '2026-01-01T00:00:00Z',"
                        + " created_at = '2026-01-01T00:00:00Z' where id in (?, ?)",
                roomLow, roomHigh);

        ChatQueryService.ChatRoomListResult result =
                chatQueryService.getRooms(USER_1, null, 20);

        List<ChatRoomResponse> items = result.items();
        assertThat(items).hasSize(2);
        // Same activityAt, so roomId DESC is the tie-breaker
        assertThat(items.get(0).roomId()).isGreaterThan(items.get(1).roomId());
    }

    @Test
    void messageLessRoomSortsByCreatedAt() {
        long roomA = newRoom(11L, 22L); // created first
        // Ensure visible separation
        jdbcTemplate.update(
                "update chat_rooms set created_at = '2025-01-01T00:00:00Z' where id = ?", roomA);

        long roomB = newRoom(11L, 33L); // created second (later)
        jdbcTemplate.update(
                "update chat_rooms set created_at = '2026-01-01T00:00:00Z' where id = ?", roomB);

        ChatQueryService.ChatRoomListResult result =
                chatQueryService.getRooms(USER_1, null, 20);

        assertThat(result.items()).hasSize(2);
        // Both have no messages; roomB has later created_at → first
        assertThat(result.items().get(0).roomId()).isEqualTo(roomB);
        assertThat(result.items().get(1).roomId()).isEqualTo(roomA);
    }

    @Test
    void hasNextFromLimitPlusOne() {
        // Create 3 rooms for pet 1
        long room1 = newRoom(11L, 22L);
        long room2 = newRoom(11L, 33L);
        long room3 = newRoom(11L, 44L);

        // Request limit 2 → should return 2 items, hasNext = true
        ChatQueryService.ChatRoomListResult result =
                chatQueryService.getRooms(USER_1, null, 2);

        assertThat(result.items()).hasSize(2);
        assertThat(result.page().hasNext()).isTrue();
    }

    @Test
    void pagingAFullSetYieldsNoDuplicatesAndNoGaps() {
        // Create 5 rooms
        long[] roomIds = new long[5];
        for (int i = 0; i < 5; i++) {
            roomIds[i] = newRoom(11L, 20L + i);
            sendText(roomIds[i], 11L, "m-" + i, "msg " + i);
        }

        String cursor = null;
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        int totalFetched = 0;
        int pages = 0;

        while (true) {
            ChatQueryService.ChatRoomListResult result =
                    chatQueryService.getRooms(USER_1, cursor, 2);
            pages++;
            for (ChatRoomResponse r : result.items()) {
                assertThat(seen.add(r.roomId()))
                        .as("duplicate room %d on page %d", r.roomId(), pages)
                        .isTrue();
            }
            totalFetched += result.items().size();
            cursor = result.page().nextCursor();
            if (!result.page().hasNext()) {
                break;
            }
        }

        assertThat(totalFetched).isEqualTo(5);
        assertThat(pages).isEqualTo(3); // 2 + 2 + 1
    }

    @Test
    void lastMessageCompleteForRoomWithMessages() {
        long roomId = newRoom(11L, 22L);
        sendText(roomId, 11L, "m1", "hello");

        ChatQueryService.ChatRoomListResult result =
                chatQueryService.getRooms(USER_1, null, 20);

        ChatRoomResponse room = result.items().get(0);
        assertThat(room.lastMessage()).isNotNull();
        assertThat(room.lastMessage().body()).isEqualTo("hello");
        assertThat(room.lastMessage().roomId()).isEqualTo(roomId);
    }

    @Test
    void lastMessageNullForMessageLessRoom() {
        long roomId = newRoom(11L, 22L);

        ChatQueryService.ChatRoomListResult result =
                chatQueryService.getRooms(USER_1, null, 20);

        ChatRoomResponse room = result.items().get(0);
        assertThat(room.lastMessage()).isNull();
    }

    @Test
    void malformedCursorReturns400() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> chatQueryService.getRooms(USER_1, "!!!not-base64!!!", 20)))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void limitDefaultsTo20WhenOmitted() {
        // Create 25 rooms
        for (int i = 0; i < 25; i++) {
            newRoom(11L, 50L + i);
        }

        // null is "parameter omitted" — an explicit 0 is rejected, see limit0Rejected...
        ChatQueryService.ChatRoomListResult result =
                chatQueryService.getRooms(USER_1, null, null);

        // Default 20 rooms per page
        assertThat(result.items()).hasSize(20);
        assertThat(result.page().hasNext()).isTrue();
    }

    @Test
    void roomListStatementCountDoesNotGrowWithRoomCount() {
        for (int i = 0; i < 2; i++) {
            long room = newRoom(PET_1, 50L + i);
            sendText(room, PET_1, "few-" + i, "hi");
        }
        long forTwoRooms = statementsForRoomList(20);

        for (int i = 2; i < 12; i++) {
            long room = newRoom(PET_1, 50L + i);
            sendText(room, PET_1, "many-" + i, "hi");
        }
        long forTwelveRooms = statementsForRoomList(20);

        // Guard the guard: if statistics were disabled both counts would be 0 and the equality
        // below would hold for the wrong reason.
        assertThat(forTwoRooms)
                .as("statement counter is not recording — this test would pass vacuously")
                .isPositive();

        // The whole point of the LATERAL join and the batch pet lookup is that neither the last
        // message nor the counterpart display data costs a query per room. If someone later
        // replaces either with a per-room call, every other test here still passes — this one
        // does not.
        assertThat(forTwelveRooms)
                .as("2 rooms took %d statements, 12 rooms took %d — the list is querying per room",
                        forTwoRooms, forTwelveRooms)
                .isEqualTo(forTwoRooms);
    }

    // ── GET /chat/rooms/{roomId} ─────────────────────────────────────────────

    @Test
    void participantCanGetRoomDetail() {
        long roomId = newRoom(11L, 22L);

        ChatRoomResponse room = chatQueryService.getRoom(USER_1, roomId);

        assertThat(room.roomId()).isEqualTo(roomId);
        assertThat(room.status()).isEqualTo("ACTIVE");
    }

    @Test
    void missingRoomReturns404() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> chatQueryService.getRoom(USER_1, 9999L)))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
    }

    @Test
    void nonParticipantReturns404() {
        long roomId = newRoom(11L, 22L); // pet 1 ↔ pet 2

        // User 2 (pet 22) is a participant, but User 3 (pet 33) is not
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> chatQueryService.getRoom(USER_3, roomId)))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
    }

    @Test
    void aPetThatLeftTheRoomLosesAccessEverywhere() {
        long roomId = newRoom(PET_1, PET_2);
        sendText(roomId, PET_1, "m1", "hello");
        assertThat(chatQueryService.getRooms(USER_1, null, 20).items()).hasSize(1);

        jdbcTemplate.update("""
                        update chat_room_participants
                           set left_at = now()
                         where room_id = ? and pet_id = ?
                        """,
                roomId, PET_1);

        // The list, the detail, and the message endpoints all read participation through the same
        // rule. Without this test, dropping the left_at filter from any one of them goes unnoticed.
        assertThat(chatQueryService.getRooms(USER_1, null, 20).items()).isEmpty();

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> chatQueryService.getRoom(USER_1, roomId)))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> chatQueryService.getMessages(USER_1, roomId, 0, 50)))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> chatQueryService.sendMessage(USER_1, roomId,
                        new ChatMessageCreateRequest("m-after-leaving", "still here?"))))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);

        // Only the leaver is cut off — the room and the counterpart are untouched.
        assertThat(chatQueryService.getRooms(USER_2, null, 20).items()).hasSize(1);
        assertThat(chatQueryService.getMessages(USER_2, roomId, 0, 50).data().items()).hasSize(1);
    }

    @Test
    void blockedDirectRoomIsHiddenAndInaccessibleInBothDirections() {
        long roomId = newRoom(PET_1, PET_2);
        sendText(roomId, PET_1, "before-block", "preserved evidence");
        jdbcTemplate.update("""
                        insert into user_blocks (
                            blocker_user_id, blocked_user_id, source_pet_id, target_pet_id
                        ) values (?, ?, ?, ?)
                        """,
                USER_1, USER_2, PET_1, PET_2);

        assertThat(chatQueryService.getRooms(USER_1, null, 20).items()).isEmpty();
        assertThat(chatQueryService.getRooms(USER_2, null, 20).items()).isEmpty();

        for (long userId : List.of(USER_1, USER_2)) {
            assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                    BusinessException.class,
                    () -> chatQueryService.getRoom(userId, roomId)))
                    .extracting(BusinessException::getErrorCode)
                    .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);

            assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                    BusinessException.class,
                    () -> chatQueryService.getMessages(userId, roomId, 0, 50)))
                    .extracting(BusinessException::getErrorCode)
                    .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);

            assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                    BusinessException.class,
                    () -> chatQueryService.sendMessage(
                            userId,
                            roomId,
                            new ChatMessageCreateRequest(
                                    "after-block-" + userId,
                                    "must not be stored"))))
                    .extracting(BusinessException::getErrorCode)
                    .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from chat_rooms where id = ?", Integer.class, roomId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from chat_messages where room_id = ?", Integer.class, roomId))
                .isEqualTo(1);
    }

    @Test
    void canSendAndSendBlockedReasonConsistentWithList() {
        long roomId = newRoom(11L, 22L);

        ChatRoomResponse fromList = chatQueryService.getRooms(USER_1, null, 20)
                .items().stream()
                .filter(r -> r.roomId() == roomId)
                .findFirst()
                .orElseThrow();

        ChatRoomResponse fromDetail = chatQueryService.getRoom(USER_1, roomId);

        assertThat(fromDetail.canSend()).isEqualTo(fromList.canSend());
        assertThat(fromDetail.sendBlockedReason()).isEqualTo(fromList.sendBlockedReason());
        // M1-015: always true / null
        assertThat(fromDetail.canSend()).isTrue();
        assertThat(fromDetail.sendBlockedReason()).isNull();
    }

    // ── GET /chat/rooms/{roomId}/messages ────────────────────────────────────

    @Test
    void omittedAfterMessageIdBehavesAsZero() {
        long roomId = newRoom(11L, 22L);
        sendText(roomId, 11L, "m1", "first");
        sendText(roomId, 11L, "m2", "second");

        ChatQueryService.ChatMessageListResult result =
                chatQueryService.getMessages(USER_1, roomId, 0, 50);

        assertThat(result.data().items()).hasSize(2);
    }

    @Test
    void onlyMessagesWithIdGreaterThanCursorReturned() {
        long roomId = newRoom(11L, 22L);
        sendText(roomId, 11L, "m1", "msg1");
        sendText(roomId, 22L, "m2", "msg2");
        sendText(roomId, 11L, "m3", "msg3");

        // Find the id of the first message
        ChatQueryService.ChatMessageListResult all =
                chatQueryService.getMessages(USER_1, roomId, 0, 50);
        long firstId = all.data().items().get(0).messageId();

        ChatQueryService.ChatMessageListResult after =
                chatQueryService.getMessages(USER_1, roomId, firstId, 50);

        assertThat(after.data().items()).hasSize(2);
        // All returned ids must be > firstId
        assertThat(after.data().items()).allMatch(m -> m.messageId() > firstId);
    }

    @Test
    void messagesReturnedInAscendingIdOrder() {
        long roomId = newRoom(11L, 22L);
        sendText(roomId, 11L, "m1", "first");
        sendText(roomId, 11L, "m2", "second");
        sendText(roomId, 11L, "m3", "third");

        ChatQueryService.ChatMessageListResult result =
                chatQueryService.getMessages(USER_1, roomId, 0, 50);

        List<Long> ids = result.data().items().stream()
                .map(ChatMessageResponse::messageId)
                .toList();
        assertThat(ids).isSorted();
    }

    @Test
    void hasMoreFromLimitPlusOne() {
        long roomId = newRoom(11L, 22L);
        for (int i = 0; i < 5; i++) {
            sendText(roomId, 11L, "m-" + i, "msg " + i);
        }

        ChatQueryService.ChatMessageListResult result =
                chatQueryService.getMessages(USER_1, roomId, 0, 3);

        assertThat(result.data().items()).hasSize(3);
        assertThat(result.data().hasMore()).isTrue();
        assertThat(result.data().nextAfterMessageId()).isNotNull();
    }

    @Test
    void nextAfterMessageIdCorrectWhenItemsExist() {
        long roomId = newRoom(11L, 22L);
        sendText(roomId, 11L, "m1", "first");
        sendText(roomId, 11L, "m2", "second");

        ChatQueryService.ChatMessageListResult result =
                chatQueryService.getMessages(USER_1, roomId, 0, 1);

        assertThat(result.data().items()).hasSize(1);
        assertThat(result.data().nextAfterMessageId())
                .isEqualTo(result.data().items().get(0).messageId());
    }

    @Test
    void emptyResultEchoesAppliedCursor() {
        long roomId = newRoom(11L, 22L);

        ChatQueryService.ChatMessageListResult result =
                chatQueryService.getMessages(USER_1, roomId, 100, 50);

        assertThat(result.data().items()).isEmpty();
        assertThat(result.data().nextAfterMessageId()).isEqualTo(100);
    }

    @Test
    void limit0RejectedWithValidationFailed() {
        long roomId = newRoom(11L, 22L);

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> chatQueryService.getMessages(USER_1, roomId, 0, 0)))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void limit101RejectedWithValidationFailed() {
        long roomId = newRoom(11L, 22L);

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> chatQueryService.getMessages(USER_1, roomId, 0, 101)))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void messagePollingStatementCountDoesNotGrowWithMessageCount() {
        long roomId = newRoom(PET_1, PET_2);
        for (int i = 0; i < 3; i++) {
            sendText(roomId, PET_1, "few-" + i, "msg " + i);
        }
        org.hibernate.stat.Statistics stats = statistics();
        stats.clear();
        chatQueryService.getMessages(USER_1, roomId, 0, 100);
        long forThreeMessages = stats.getPrepareStatementCount();

        for (int i = 3; i < 40; i++) {
            sendText(roomId, PET_1, "many-" + i, "msg " + i);
        }
        stats.clear();
        chatQueryService.getMessages(USER_1, roomId, 0, 100);
        long forFortyMessages = stats.getPrepareStatementCount();

        assertThat(forThreeMessages)
                .as("statement counter is not recording — this test would pass vacuously")
                .isPositive();

        // ChatMessage.room is a lazy @ManyToOne and the response carries roomId, so building the
        // page must not touch the room per message.
        assertThat(forFortyMessages)
                .as("3 messages took %d statements, 40 took %d — the page is querying per message",
                        forThreeMessages, forFortyMessages)
                .isEqualTo(forThreeMessages);
    }

    @Test
    void nonParticipantMessagesReturns404() {
        long roomId = newRoom(11L, 22L); // pet 1 ↔ pet 2, User 3 is not a participant

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> chatQueryService.getMessages(USER_3, roomId, 0, 50)))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
    }

    // ── POST /chat/rooms/{roomId}/messages ────────────────────────────────────

    @Test
    void sendingIntoArchivedRoomRestoresItToActive() {
        long roomId = newRoom(11L, 22L);
        jdbcTemplate.update(
                "update chat_rooms set status = 'ARCHIVED', archived_at = now() where id = ?",
                roomId);

        ChatQueryService.SendMessageResult result = chatQueryService.sendMessage(
                USER_1, roomId, new ChatMessageCreateRequest("m-restore", "hello again"));

        assertThat(result.data().roomId()).isEqualTo(roomId);
        assertThat(jdbcTemplate.queryForObject(
                "select status from chat_rooms where id = ?", String.class, roomId))
                .isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "select archived_at from chat_rooms where id = ?", java.time.Instant.class, roomId))
                .isNull();
    }

    @Test
    void storedSenderPetIdEqualsActorsPetId() {
        long roomId = newRoom(11L, 22L);

        ChatQueryService.SendMessageResult result = chatQueryService.sendMessage(
                USER_1, roomId, new ChatMessageCreateRequest("m-sender", "hello"));

        assertThat(result.data().senderPetId()).isEqualTo(11L);
        assertThat(result.data().senderType()).isEqualTo("PET");
    }

    @Test
    void changingActivePetLeavesStoredMessagesUntouched() {
        long roomId = newRoom(PET_1, PET_2);
        ChatQueryService.SendMessageResult sent = chatQueryService.sendMessage(
                USER_1, roomId, new ChatMessageCreateRequest("m-before", "sent as pet 11"));
        assertThat(sent.data().senderPetId()).isEqualTo(PET_1);

        // PUT /me/active-pet makes this a real runtime transition, not a hypothetical one.
        // The new pet is not a participant of this room.
        insertPet(12L, USER_1);
        setActivePet(USER_1, 12L);

        // The counterpart still sees the original sender — the stored row did not follow the switch.
        ChatQueryService.ChatMessageListResult asCounterpart =
                chatQueryService.getMessages(USER_2, roomId, 0, 50);
        assertThat(asCounterpart.data().items()).hasSize(1);
        assertThat(asCounterpart.data().items().get(0).senderPetId()).isEqualTo(PET_1);
        assertThat(asCounterpart.data().items().get(0).body()).isEqualTo("sent as pet 11");

        // And the switched-to pet has no standing in a room it never joined.
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> chatQueryService.getMessages(USER_1, roomId, 0, 50)))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> chatQueryService.sendMessage(USER_1, roomId,
                        new ChatMessageCreateRequest("m-after", "sent as pet 12"))))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
    }

    @Test
    void sameClientMessageIdDifferentBodyRejectedWith409() {
        long roomId = newRoom(11L, 22L);

        chatQueryService.sendMessage(USER_1, roomId,
                new ChatMessageCreateRequest("m-dup", "original"));

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> chatQueryService.sendMessage(USER_1, roomId,
                        new ChatMessageCreateRequest("m-dup", "different body"))))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.CHAT_DUPLICATE_MESSAGE);
    }

    @Test
    void nonParticipantSendReturns404NotChatSenderNotParticipant() {
        long roomId = newRoom(11L, 22L); // User 3's pet (33) is not a participant

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> chatQueryService.sendMessage(USER_3, roomId,
                        new ChatMessageCreateRequest("m-nope", "hi"))))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
    }

    @Test
    void suspendedAndDeletedCounterpartsStillRender() {
        long suspendedRoom = newRoom(PET_1, 20L);
        long deletedRoom = newRoom(PET_1, 21L);
        sendText(suspendedRoom, PET_1, "m1", "hello");
        sendText(deletedRoom, PET_1, "m2", "hello");

        // A past conversation must keep rendering its counterpart even once that pet is gone.
        jdbcTemplate.update("update pets set status = 'SUSPENDED' where id = 20");
        jdbcTemplate.update(
                "update pets set status = 'DELETED', deleted_at = now() where id = 21");

        ChatQueryService.ChatRoomListResult result =
                chatQueryService.getRooms(USER_1, null, 20);

        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).allSatisfy(room -> {
            assertThat(room.counterpartPet()).isNotNull();
            assertThat(room.counterpartPet().nickname()).isNotBlank();
        });
    }

    @Test
    void cursorRoundTripsThroughCodec() {
        // Two rooms so the first page genuinely has a successor — a cursor is only emitted
        // when another page exists.
        long roomA = newRoom(PET_1, PET_2);
        long roomB = newRoom(PET_1, PET_3);
        sendText(roomA, PET_1, "m-a", "in A");
        sendText(roomB, PET_1, "m-b", "in B");

        ChatQueryService.ChatRoomListResult page1 =
                chatQueryService.getRooms(USER_1, null, 1);
        assertThat(page1.items()).hasSize(1);
        assertThat(page1.page().hasNext()).isTrue();

        String encoded = page1.page().nextCursor();
        assertThat(encoded).isNotNull();

        ChatQueryService.ChatRoomListResult page2 =
                chatQueryService.getRooms(USER_1, encoded, 1);

        assertThat(page2.items()).hasSize(1);
        assertThat(page2.items().get(0).roomId())
                .isNotEqualTo(page1.items().get(0).roomId());
    }
}
