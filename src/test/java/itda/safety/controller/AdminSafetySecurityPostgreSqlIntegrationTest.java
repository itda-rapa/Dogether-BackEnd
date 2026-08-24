package itda.safety.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.common.security.CurrentUser;
import itda.user.domain.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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
        "spring.flyway.locations=classpath:db/migration",
        "app.safety.evaluator.enabled=false"
})
class AdminSafetySecurityPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final long SUBJECT_ID = 10L;
    private static final long TARGET_ID = 20L;
    private static final long USER_ID = 30L;
    private static final long ADMIN_ID = 40L;
    private static final long SUPER_ADMIN_ID = 50L;
    private static final long CASE_ID = 1L;
    private static final String NEIGHBORHOOD = "4113111500";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate safety_review_cases restart identity cascade");
        jdbc.execute("truncate users restart identity cascade");
        jdbc.update("""
                insert into neighborhoods (
                    code, sido_name, sigungu_name, eupmyeondong_name, active
                ) values (?, '경기도', '성남시', '테스트동', true)
                on conflict (code) do nothing
                """, NEIGHBORHOOD);
        insertUser(SUBJECT_ID, Role.USER);
        insertUser(TARGET_ID, Role.USER);
        insertUser(USER_ID, Role.USER);
        insertUser(ADMIN_ID, Role.ADMIN);
        insertUser(SUPER_ADMIN_ID, Role.SUPER_ADMIN);
        jdbc.update("""
                insert into safety_review_cases (
                    id, subject_user_id, target_user_id, status, total_score, signal_count,
                    primary_signal_type, evaluation_policy_version, first_detected_at,
                    last_detected_at, last_evaluated_event_id, evaluated_at
                ) values (?, ?, ?, 'OPEN', 40, 2, 'USER_BLOCKED', 1,
                          current_timestamp - interval '1 minute', current_timestamp,
                          100, current_timestamp)
                """, CASE_ID, SUBJECT_ID, TARGET_ID);
    }

    @Test
    void ordinaryUserCannotAccessAnySafetyAdminApi() throws Exception {
        CurrentUser ordinary = principal(USER_ID, Role.USER);

        mockMvc.perform(get("/admin/safety/cases").with(user(ordinary)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/safety/cases/{caseId}", CASE_ID).with(user(ordinary)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/safety/cases/{caseId}/evidence", CASE_ID)
                        .param("purpose", "fact check").with(user(ordinary)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/safety/cases/{caseId}/actions", CASE_ID)
                        .with(user(ordinary))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":\"DISMISSED\",\"reason\":\"not allowed\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void activeAdminCanUseSafetyQueryEvidenceAndActionApis() throws Exception {
        CurrentUser admin = principal(ADMIN_ID, Role.ADMIN);

        mockMvc.perform(get("/admin/safety/cases").with(user(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/safety/cases/{caseId}", CASE_ID).with(user(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/safety/cases/{caseId}/evidence", CASE_ID)
                        .param("purpose", "fact check").with(user(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/safety/cases/{caseId}/actions", CASE_ID)
                        .with(user(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":\"DISMISSED\",\"reason\":\"false positive\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void activeSuperAdminCanUseSafetyQueue() throws Exception {
        mockMvc.perform(get("/admin/safety/cases")
                        .with(user(principal(SUPER_ADMIN_ID, Role.SUPER_ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    void principalRoleDoesNotBypassDatabaseRoleOrAccountStatus() throws Exception {
        jdbc.update("update users set role = 'USER' where id = ?", ADMIN_ID);
        jdbc.update("update users set account_status = 'SUSPENDED' where id = ?", SUPER_ADMIN_ID);

        mockMvc.perform(get("/admin/safety/cases")
                        .with(user(principal(ADMIN_ID, Role.ADMIN))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/safety/cases")
                        .with(user(principal(SUPER_ADMIN_ID, Role.SUPER_ADMIN))))
                .andExpect(status().isForbidden());
    }

    private void insertUser(long userId, Role role) {
        jdbc.update("""
                insert into users (
                    id, email, password_hash, nickname, public_tag,
                    role, account_status, neighborhood_code
                ) values (?, ?, 'encoded', ?, ?, ?, 'ACTIVE', ?)
                """, userId, "user" + userId + "@test.com", "사용자" + userId,
                "user" + userId + "#" + String.format("%04d", userId),
                role.name(), NEIGHBORHOOD);
    }

    private static CurrentUser principal(long userId, Role role) {
        return new CurrentUser(userId, "user" + userId + "@test.com", role);
    }
}
