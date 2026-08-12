package itda.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.common.exception.BusinessException;
import itda.common.security.CurrentUser;
import itda.report.domain.AdminActionType;
import itda.report.dto.AdminReportActionRequest;
import itda.report.service.AdminReportService;
import itda.user.domain.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class AdminReportPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final long REPORT_ID = 1L;
    private static final long ADMIN_ID = 99L;
    private static final long USER_1 = 1L;
    private static final long USER_2 = 2L;
    private static final long PET_1 = 11L;
    private static final long PET_2 = 22L;
    private static final long ROOM_ID = 1L;
    private static final String NEIGHBORHOOD = "4113111500";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdminReportService adminReportService;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                truncate admin_actions, reports, chat_messages, chat_room_participants,
                         chat_rooms, pets, users
                restart identity cascade
                """);
        insertUser(USER_1, "USER");
        insertUser(USER_2, "USER");
        insertUser(ADMIN_ID, "ADMIN");
        insertPet(PET_1, USER_1);
        insertPet(PET_2, USER_2);
        jdbcTemplate.update("update users set active_pet_id = ? where id = ?", PET_1, USER_1);
        jdbcTemplate.update("update users set active_pet_id = ? where id = ?", PET_2, USER_2);
        jdbcTemplate.update("""
                insert into chat_rooms (id, type, status, origin, pet_low_id, pet_high_id)
                values (?, 'DIRECT', 'ACTIVE', 'GREETING', ?, ?)
                """, ROOM_ID, PET_1, PET_2);
        jdbcTemplate.update("""
                insert into chat_room_participants (room_id, pet_id)
                values (?, ?), (?, ?)
                """, ROOM_ID, PET_1, ROOM_ID, PET_2);
        jdbcTemplate.update("""
                insert into chat_messages (
                    id, room_id, sender_type, sender_pet_id, type, body, client_message_id
                ) values
                    (1, ?, 'PET', ?, 'TEXT', 'first evidence', 'client-1'),
                    (2, ?, 'PET', ?, 'TEXT', 'second evidence', 'client-2')
                """, ROOM_ID, PET_1, ROOM_ID, PET_2);
        jdbcTemplate.update("""
                insert into reports (
                    id, reporter_user_id, reporter_pet_id, reported_user_id, reported_pet_id,
                    room_id, reason_code, detail, status
                ) values (?, ?, ?, ?, ?, ?, 'HARASSMENT', 'admin evidence', 'OPEN')
                """, REPORT_ID, USER_1, PET_1, USER_2, PET_2, ROOM_ID);
    }

    @Test
    @DisplayName("일반 사용자는 신고 큐·증거·처리 API에 접근할 수 없다")
    void userCannotUseAdminReportApis() throws Exception {
        CurrentUser ordinaryUser = principal(USER_1, Role.USER);

        mockMvc.perform(get("/admin/reports").with(user(ordinaryUser)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/reports/{id}", REPORT_ID).with(user(ordinaryUser)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/reports/{id}/actions", REPORT_ID)
                        .with(user(ordinaryUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":\"DISMISSED\",\"reason\":\"no issue\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN principal이어도 DB role이 USER면 관리자 신고 API를 사용할 수 없다")
    void dbRoleRevalidationDeniesAdminApis() throws Exception {
        jdbcTemplate.update("update users set role = 'USER' where id = ?", ADMIN_ID);
        CurrentUser adminPrincipal = principal(ADMIN_ID, Role.ADMIN);

        mockMvc.perform(get("/admin/reports").with(user(adminPrincipal)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/reports/{id}", REPORT_ID).with(user(adminPrincipal)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/reports/{id}/actions", REPORT_ID)
                        .with(user(adminPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":\"DISMISSED\",\"reason\":\"no issue\"}"))
                .andExpect(status().isForbidden());

        assertThat(jdbcTemplate.queryForObject(
                "select status from reports where id = ?", String.class, REPORT_ID
        )).isEqualTo("OPEN");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from admin_actions where target_id = ?", Long.class, REPORT_ID
        )).isZero();
    }

    @Test
    @DisplayName("관리자 신고 큐는 상태 필터와 offset 페이지를 적용한다")
    void listSupportsStatusFilterAndOffsetPage() throws Exception {
        jdbcTemplate.update("""
                insert into reports (
                    id, reporter_user_id, reporter_pet_id, reported_user_id, reported_pet_id,
                    room_id, reason_code, status
                ) values (?, ?, ?, ?, ?, ?, 'SPAM', 'OPEN')
                """, 2L, USER_2, PET_2, USER_1, PET_1, ROOM_ID);

        mockMvc.perform(get("/admin/reports")
                        .with(user(principal(ADMIN_ID, Role.ADMIN)))
                        .param("status", "OPEN")
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.page.page").value(0))
                .andExpect(jsonPath("$.data.page.size").value(1))
                .andExpect(jsonPath("$.data.page.totalElements").value(2));
    }

    @Test
    @DisplayName("관리자 증거 조회는 양쪽 User/Pet·방·전체 메시지를 관리자 DTO로 반환한다")
    void detailReturnsEvidenceAndAllMessages() throws Exception {
        mockMvc.perform(get("/admin/reports/{id}", REPORT_ID)
                        .with(user(principal(ADMIN_ID, Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report.reportId").value(REPORT_ID))
                .andExpect(jsonPath("$.data.reporter.userId").value(USER_1))
                .andExpect(jsonPath("$.data.reported.userId").value(USER_2))
                .andExpect(jsonPath("$.data.room.roomId").value(ROOM_ID))
                .andExpect(jsonPath("$.data.room.participantPetIds.length()").value(2))
                .andExpect(jsonPath("$.data.messages.length()").value(2))
                .andExpect(jsonPath("$.data.messages[0].body").value("first evidence"));
    }

    @Test
    @DisplayName("DISMISSED는 NO_ACTION과 관리자 처리 이력을 저장한다")
    void dismissedClosesReportAndWritesHistory() throws Exception {
        mockMvc.perform(post("/admin/reports/{id}/actions", REPORT_ID)
                        .with(user(principal(ADMIN_ID, Role.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":\"DISMISSED\",\"reason\":\"no violation\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NO_ACTION"))
                .andExpect(jsonPath("$.data.reviewedByAdminId").value(ADMIN_ID))
                .andExpect(jsonPath("$.data.resolutionNote").value("no violation"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from admin_actions where target_type = 'REPORT' and target_id = ?",
                Long.class,
                REPORT_ID
        )).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select status from reports where id = ?", String.class, REPORT_ID
        )).isEqualTo("NO_ACTION");

        Map<String, Object> action = jdbcTemplate.queryForMap("""
                select actor_admin_id,
                       target_type,
                       target_id,
                       action_type,
                       reason,
                       before_state ->> 'status' as before_status,
                       jsonb_typeof(before_state -> 'reviewedByAdminId') as before_reviewed_by_type,
                       jsonb_typeof(before_state -> 'reviewedAt') as before_reviewed_at_type,
                       jsonb_typeof(before_state -> 'resolutionNote') as before_resolution_note_type,
                       after_state ->> 'status' as after_status,
                       (after_state ->> 'reviewedByAdminId')::bigint as after_reviewed_by_admin_id,
                       jsonb_typeof(after_state -> 'reviewedAt') as after_reviewed_at_type,
                       after_state ->> 'resolutionNote' as after_resolution_note,
                       created_at
                from admin_actions
                where target_type = 'REPORT' and target_id = ?
                """, REPORT_ID);

        assertThat(action.get("actor_admin_id")).isEqualTo(ADMIN_ID);
        assertThat(action.get("target_type")).isEqualTo("REPORT");
        assertThat(action.get("target_id")).isEqualTo(REPORT_ID);
        assertThat(action.get("action_type")).isEqualTo("DISMISSED");
        assertThat(action.get("reason")).isEqualTo("no violation");
        assertThat(action.get("before_status")).isEqualTo("OPEN");
        assertThat(action.get("before_reviewed_by_type")).isEqualTo("null");
        assertThat(action.get("before_reviewed_at_type")).isEqualTo("null");
        assertThat(action.get("before_resolution_note_type")).isEqualTo("null");
        assertThat(action.get("after_status")).isEqualTo("NO_ACTION");
        assertThat(action.get("after_reviewed_by_admin_id")).isEqualTo(ADMIN_ID);
        assertThat(action.get("after_reviewed_at_type")).isNotEqualTo("null");
        assertThat(action.get("after_resolution_note")).isEqualTo("no violation");
        assertThat(action.get("created_at")).isNotNull();
    }

    @Test
    @DisplayName("WARNING은 계정 상태를 바꾸지 않고 ACTIONED로 종결한다")
    void warningClosesReportWithoutChangingAccount() throws Exception {
        mockMvc.perform(post("/admin/reports/{id}/actions", REPORT_ID)
                        .with(user(principal(ADMIN_ID, Role.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":\"WARNING\",\"reason\":\"warning recorded\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIONED"));

        assertThat(jdbcTemplate.queryForObject(
                "select account_status from users where id = ?", String.class, USER_2
        )).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("종결된 신고는 재처리할 수 없고 409와 이력 하나만 남긴다")
    void resolvedReportCannotBeProcessedAgain() throws Exception {
        resolve(AdminActionType.DISMISSED, "first decision");

        mockMvc.perform(post("/admin/reports/{id}/actions", REPORT_ID)
                        .with(user(principal(ADMIN_ID, Role.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":\"WARNING\",\"reason\":\"second decision\"}"))
                .andExpect(status().isConflict());

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from admin_actions where target_id = ?", Long.class, REPORT_ID
        )).isEqualTo(1L);
    }

    @Test
    @DisplayName("동시 처리에서는 하나만 성공한다")
    void concurrentResolutionHasOneWinner() throws Exception {
        int workers = 2;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(executor.submit((Callable<Boolean>) () -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                    adminReportService.resolveReport(
                            ADMIN_ID,
                            REPORT_ID,
                            new AdminReportActionRequest(AdminActionType.WARNING, "race")
                    );
                    return true;
                } catch (BusinessException exception) {
                    return false;
                }
            }));
        }
        start.countDown();

        List<Boolean> results = new ArrayList<>();
        for (Future<Boolean> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        executor.shutdownNow();

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from admin_actions where target_id = ?", Long.class, REPORT_ID
        )).isEqualTo(1L);
    }

    private void resolve(AdminActionType actionType, String reason) throws Exception {
        mockMvc.perform(post("/admin/reports/{id}/actions", REPORT_ID)
                        .with(user(principal(ADMIN_ID, Role.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":\"%s\",\"reason\":\"%s\"}"
                                .formatted(actionType, reason)))
                .andExpect(status().isOk());
    }

    private CurrentUser principal(long userId, Role role) {
        return new CurrentUser(userId, "user" + userId + "@test.com", role);
    }

    private void insertUser(long userId, String role) {
        jdbcTemplate.update("""
                        insert into users (
                            id, email, password_hash, nickname, public_tag,
                            role, account_status, neighborhood_code
                        ) values (?, ?, 'encoded', ?, ?, ?, 'ACTIVE', ?)
                        """,
                userId,
                "user" + userId + "@test.com",
                "사용자" + userId,
                "user" + userId + "#" + String.format("%04d", userId),
                role,
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
}
