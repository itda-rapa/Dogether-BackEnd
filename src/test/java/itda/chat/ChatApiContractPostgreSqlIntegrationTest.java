package itda.chat;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import itda.chat.domain.RoomOrigin;
import itda.chat.dto.ChatMessageCreateRequest;
import itda.chat.service.ChatMessageService;
import itda.chat.service.ChatRoomService;
import itda.common.security.CurrentUser;
import itda.user.domain.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * HTTP contract tests for the M1-015 chat endpoints.
 *
 * <p>The sibling {@code ChatRestPollingApiPostgreSqlIntegrationTest} drives the service layer and
 * covers behaviour. This class exists for what only shows up over the wire: status codes, JSON
 * shape, and parameter binding.
 *
 * <p>The repeated {@code data.data} assertions are deliberate. {@code ApiResponse} already supplies
 * the {@code data} envelope, and an inner wrapper record silently nesting a second one is a defect
 * this suite has seen twice; it is invisible to a service-level test.
 */
@Tag("postgres")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class ChatApiContractPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChatRoomService chatRoomService;

    @Autowired
    private ChatMessageService chatMessageService;

    private static final long USER_1 = 1L;
    private static final long USER_2 = 2L;
    private static final long PET_1 = 11L;
    private static final long PET_2 = 22L;
    private static final String NEIGHBORHOOD = "4113111500";

    private long roomId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                truncate users, pets, chat_messages, chat_room_participants, chat_rooms
                restart identity cascade
                """);
        insertUser(USER_1);
        insertUser(USER_2);
        insertPet(PET_1, USER_1);
        insertPet(PET_2, USER_2);
        jdbcTemplate.update("update users set active_pet_id = ? where id = ?", PET_1, USER_1);
        jdbcTemplate.update("update users set active_pet_id = ? where id = ?", PET_2, USER_2);

        roomId = chatRoomService.ensureDirectRoom(PET_1, PET_2, RoomOrigin.GREETING).roomId();
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

    private CurrentUser principal(long userId) {
        return new CurrentUser(userId, "user" + userId + "@test.com", Role.USER);
    }

    private String body(String clientMessageId, String text) {
        return """
                {"clientMessageId":"%s","type":"TEXT","body":"%s"}
                """.formatted(clientMessageId, text);
    }

    // ── POST /chat/rooms/{roomId}/messages ────────────────────────────────────

    @Test
    @DisplayName("새 메시지는 201과 ChatMessage 형태로 반환된다")
    void newMessageReturns201WithMessageShape() throws Exception {
        jdbcTemplate.update("update pets set nickname = 'Mong' where id = ?", PET_1);

        mockMvc.perform(post("/chat/rooms/{roomId}/messages", roomId)
                        .with(user(principal(USER_1)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("c-1", "hello")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.messageId").isNumber())
                .andExpect(jsonPath("$.data.roomId").value((int) roomId))
                .andExpect(jsonPath("$.data.senderPetId").value((int) PET_1))
                .andExpect(jsonPath("$.data.senderPetNickname").value("Mong"))
                .andExpect(jsonPath("$.data.type").value("TEXT"))
                .andExpect(jsonPath("$.data.body").value("hello"))
                .andExpect(jsonPath("$.data.createdAt").exists())
                // the envelope must not be nested twice, and the entity must not leak
                .andExpect(jsonPath("$.data.data").doesNotExist())
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.room").doesNotExist());
    }

    @Test
    @DisplayName("같은 clientMessageId 재전송은 200과 동일 messageId를 반환한다")
    void idempotentRetryReturns200WithSameMessageId() throws Exception {
        String payload = body("c-dup", "hello");

        String first = mockMvc.perform(post("/chat/rooms/{roomId}/messages", roomId)
                        .with(user(principal(USER_1)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long firstId = ((Number) JsonPath.read(first, "$.data.messageId")).longValue();

        mockMvc.perform(post("/chat/rooms/{roomId}/messages", roomId)
                        .with(user(principal(USER_1)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messageId").value((int) firstId));
    }

    @Test
    @DisplayName("요청 본문의 senderPetId는 무시되고 Active Pet이 발신자가 된다")
    void senderPetIdInBodyIsIgnored() throws Exception {
        String forged = """
                {"clientMessageId":"c-forge","body":"hi","senderPetId":%d,"type":"TEXT"}
                """.formatted(PET_2);

        mockMvc.perform(post("/chat/rooms/{roomId}/messages", roomId)
                        .with(user(principal(USER_1)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forged))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.senderPetId").value((int) PET_1))
                .andExpect(jsonPath("$.data.type").value("TEXT"));
    }

    @Test
    @DisplayName("type이 없는 legacy TEXT 요청은 TEXT로 정규화되어 201을 준다")
    void legacyTextWithoutTypeIsCreated() throws Exception {
        mockMvc.perform(post("/chat/rooms/{roomId}/messages", roomId)
                        .with(user(principal(USER_1)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"legacy-1\",\"body\":\"안녕\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("TEXT"))
                .andExpect(jsonPath("$.data.body").value("안녕"));
    }

    @Test
    @DisplayName("legacy TEXT 재전송은 기존 메시지를 200으로 반환한다")
    void legacyTextResendReturns200WithSameMessageId() throws Exception {
        String payload = "{\"clientMessageId\":\"legacy-dup\",\"body\":\"안녕\"}";
        String first = mockMvc.perform(post("/chat/rooms/{roomId}/messages", roomId)
                        .with(user(principal(USER_1)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long firstId = ((Number) JsonPath.read(first, "$.data.messageId")).longValue();

        mockMvc.perform(post("/chat/rooms/{roomId}/messages", roomId)
                        .with(user(principal(USER_1)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messageId").value((int) firstId));
    }

    @Test
    @DisplayName("type 누락 + mediaId는 400으로 거부된다")
    void typeMissingWithMediaIdIsRejected() throws Exception {
        mockMvc.perform(post("/chat/rooms/{roomId}/messages", roomId)
                        .with(user(principal(USER_1)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"legacy-m\",\"mediaId\":501}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CHAT_MESSAGE_PAYLOAD_INVALID"));
    }

    @Test
    @DisplayName("type 누락 + setlogId는 400으로 거부된다")
    void typeMissingWithSetlogIdIsRejected() throws Exception {
        mockMvc.perform(post("/chat/rooms/{roomId}/messages", roomId)
                        .with(user(principal(USER_1)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"legacy-s\",\"setlogId\":77}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CHAT_MESSAGE_PAYLOAD_INVALID"));
    }

    // ── GET /chat/rooms/{roomId} ──────────────────────────────────────────────

    @Test
    @DisplayName("방 상세는 data 바로 아래에 ChatRoom을 담는다")
    void roomDetailIsNotDoubleWrapped() throws Exception {
        mockMvc.perform(get("/chat/rooms/{roomId}", roomId)
                        .with(user(principal(USER_1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value((int) roomId))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.counterpartPet.petId").value((int) PET_2))
                .andExpect(jsonPath("$.data.canSend").isBoolean())
                .andExpect(jsonPath("$.data.data").doesNotExist());
    }

    @Test
    @DisplayName("참가자가 아니면 방 존재를 숨기고 404를 준다")
    void nonParticipantGetsNotFound() throws Exception {
        insertUser(3L);
        insertPet(33L, 3L);
        jdbcTemplate.update("update users set active_pet_id = ? where id = ?", 33L, 3L);

        mockMvc.perform(get("/chat/rooms/{roomId}", roomId)
                        .with(user(principal(3L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("CHAT_ROOM_NOT_FOUND"));
    }

    // ── GET /chat/rooms ───────────────────────────────────────────────────────

    @Test
    @DisplayName("방 목록은 items와 page를 담는다")
    void roomListCarriesItemsAndPage() throws Exception {
        chatMessageService.sendText(roomId, PET_1,
                new ChatMessageCreateRequest("c-list", "hello"));

        mockMvc.perform(get("/chat/rooms").with(user(principal(USER_1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[0].roomId").value((int) roomId))
                .andExpect(jsonPath("$.data.items[0].lastMessage.body").value("hello"))
                .andExpect(jsonPath("$.data.page.hasNext").isBoolean())
                .andExpect(jsonPath("$.data.data").doesNotExist());
    }

    // ── GET /chat/rooms/{roomId}/messages ─────────────────────────────────────

    @Test
    @DisplayName("메시지 목록은 items·nextAfterMessageId·hasMore를 담는다")
    void messageListCarriesPollingFields() throws Exception {
        chatMessageService.sendText(roomId, PET_1,
                new ChatMessageCreateRequest("c-a", "first"));

        mockMvc.perform(get("/chat/rooms/{roomId}/messages", roomId)
                        .with(user(principal(USER_1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[0].body").value("first"))
                .andExpect(jsonPath("$.data.items[0].type").value("TEXT"))
                .andExpect(jsonPath("$.data.nextAfterMessageId").isNumber())
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.data").doesNotExist());
    }

    @Test
    @DisplayName("limit 파라미터 생략과 0 명시는 다르게 처리된다")
    void omittedLimitDiffersFromExplicitZero() throws Exception {
        mockMvc.perform(get("/chat/rooms/{roomId}/messages", roomId)
                        .with(user(principal(USER_1))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/chat/rooms/{roomId}/messages", roomId)
                        .param("limit", "0")
                        .with(user(principal(USER_1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private String cursorOf(String json) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void expectCursorRejected(String json) throws Exception {
        mockMvc.perform(get("/chat/rooms")
                        .param("cursor", cursorOf(json))
                        .with(user(principal(USER_1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("타임스탬프가 깨진 커서는 500이 아니라 400을 준다")
    void craftedCursorIsRejectedWith400() throws Exception {
        // reaches CAST(... AS TIMESTAMPTZ) and blows up in SQL if not validated first
        expectCursorRejected("{\"v\":1,\"activityAt\":\"abc\",\"roomId\":1}");
    }

    @Test
    @DisplayName("숫자가 넘치는 커서도 500이 아니라 400을 준다")
    void numericallyOverflowingCursorIsRejectedWith400() throws Exception {
        // the payload pattern accepts unbounded digit runs, so parsing must not be able to
        // escape as a server fault
        expectCursorRejected("{\"v\":99999999999999,\"activityAt\":\"2026-07-28T09:10:00Z\",\"roomId\":1}");
        expectCursorRejected(
                "{\"v\":1,\"activityAt\":\"2026-07-28T09:10:00Z\",\"roomId\":999999999999999999999999}");
    }
}
