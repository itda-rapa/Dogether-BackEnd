package itda.report;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.chat.domain.RoomOrigin;
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
 * HTTP contract tests for {@code POST /reports}.
 *
 * <p>The sibling {@code ReportCreatePostgreSqlIntegrationTest} drives the service layer and covers
 * behaviour. This class exists for what only shows up over the wire: status codes, JSON shape, and
 * the nested-data guard.
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
class ReportCreateApiContractPostgreSqlIntegrationTest {

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

    private static final long USER_1 = 1L;
    private static final long USER_2 = 2L;
    private static final long PET_1 = 11L;
    private static final long PET_2 = 22L;
    private static final String NEIGHBORHOOD = "4113111500";

    private long roomId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                truncate reports, chat_messages, chat_room_participants,
                         chat_rooms, pets, users
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

    private String reportBody(String reasonCode, String detail) {
        if (detail == null) {
            return """
                    {"roomId":%d,"reasonCode":"%s"}
                    """.formatted(roomId, reasonCode);
        }
        return """
                {"roomId":%d,"reasonCode":"%s","detail":"%s"}
                """.formatted(roomId, reasonCode, detail);
    }

    private String reportBodyRaw(long roomId, String reasonCode, String detail) {
        if (detail == null) {
            return """
                    {"roomId":%d,"reasonCode":"%s"}
                    """.formatted(roomId, reasonCode);
        }
        return """
                {"roomId":%d,"reasonCode":"%s","detail":"%s"}
                """.formatted(roomId, reasonCode, detail);
    }

    // ── 201 / 200 분기 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("인증 없이 신고하면 401")
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(post("/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBody("HARASSMENT", null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("정의되지 않은 신고 사유는 400 + VALIDATION_FAILED")
    void invalidReasonReturns400() throws Exception {
        mockMvc.perform(post("/reports")
                        .with(user(principal(USER_1)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBody("UNKNOWN_REASON", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("신규 신고는 201과 Report 형태로 반환된다")
    void newReportReturns201() throws Exception {
        mockMvc.perform(post("/reports")
                        .with(user(principal(USER_1)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBody("HARASSMENT", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reportId").isNumber())
                .andExpect(jsonPath("$.data.roomId").value((int) roomId))
                .andExpect(jsonPath("$.data.reasonCode").value("HARASSMENT"))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.createdAt").exists())
                // 이중 래핑 방지
                .andExpect(jsonPath("$.data.data").doesNotExist())
                // 엔티티 필드 누출 방지
                .andExpect(jsonPath("$.data.id").doesNotExist());
    }

    @Test
    @DisplayName("동일 reporter·room 재신고는 200과 기존 reportId를 반환한다")
    void resubmitReturns200() throws Exception {
        // 첫 신고 → 201
        mockMvc.perform(post("/reports")
                        .with(user(principal(USER_1)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBody("HARASSMENT", "first")))
                .andExpect(status().isCreated());

        // 재신고 → 200, 같은 reportId
        mockMvc.perform(post("/reports")
                        .with(user(principal(USER_1)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBody("SPAM", "different")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reasonCode").value("HARASSMENT"))
                .andExpect(jsonPath("$.data.detail").value("first"))
                .andExpect(jsonPath("$.data.data").doesNotExist());
    }

    // ── 404 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("방 참가자가 아니면 404 + CHAT_ROOM_NOT_FOUND")
    void nonParticipantReturns404ChatRoomNotFound() throws Exception {
        jdbcTemplate.update("""
                        insert into users (id, email, password_hash, nickname, public_tag,
                                           role, account_status, neighborhood_code)
                        values (3, 'user3@test.com', 'encoded', '사용자3', 'user3#0003',
                                'USER', 'ACTIVE', ?)
                        """, NEIGHBORHOOD);
        jdbcTemplate.update("""
                        insert into pets (id, owner_user_id, public_tag, nickname, status)
                        values (33, 3, 'pet33#0033', '펫33', 'ACTIVE')
                        """);
        jdbcTemplate.update("update users set active_pet_id = 33 where id = 3");

        mockMvc.perform(post("/reports")
                        .with(user(principal(3L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBody("SPAM", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHAT_ROOM_NOT_FOUND"));
    }

    @Test
    @DisplayName("존재하지 않는 방은 404 + CHAT_ROOM_NOT_FOUND")
    void nonexistentRoomReturns404() throws Exception {
        mockMvc.perform(post("/reports")
                        .with(user(principal(USER_1)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBodyRaw(9999L, "SPAM", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHAT_ROOM_NOT_FOUND"));
    }
}
