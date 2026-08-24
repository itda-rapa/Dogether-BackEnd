package itda.dashboard.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
class AdminDashboardSecurityPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final long USER_ID = 710L;
    private static final long ADMIN_ID = 720L;
    private static final long SUPER_ADMIN_ID = 730L;
    private static final String NEIGHBORHOOD = "4113111500";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate users restart identity cascade");
        jdbc.update("""
                insert into neighborhoods (
                    code, sido_name, sigungu_name, eupmyeondong_name, active
                ) values (?, '경기도', '성남시', '테스트동', true)
                on conflict (code) do nothing
                """, NEIGHBORHOOD);
        insertUser(USER_ID, Role.USER);
        insertUser(ADMIN_ID, Role.ADMIN);
        insertUser(SUPER_ADMIN_ID, Role.SUPER_ADMIN);
    }

    @Test
    void ordinaryUserIsRejectedByTheAdminRoute() throws Exception {
        mockMvc.perform(get("/admin/dashboard").with(user(principal(USER_ID, Role.USER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void activeAdminAndSuperAdminCanReadTheDashboard() throws Exception {
        mockMvc.perform(get("/admin/dashboard").with(user(principal(ADMIN_ID, Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(get("/admin/dashboard")
                        .with(user(principal(SUPER_ADMIN_ID, Role.SUPER_ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    void staleJwtRoleCannotBypassTheCurrentDatabaseRoleOrStatus() throws Exception {
        jdbc.update("update users set role = 'USER' where id = ?", ADMIN_ID);
        jdbc.update("update users set account_status = 'SUSPENDED' where id = ?", SUPER_ADMIN_ID);

        mockMvc.perform(get("/admin/dashboard").with(user(principal(ADMIN_ID, Role.ADMIN))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(get("/admin/dashboard")
                        .with(user(principal(SUPER_ADMIN_ID, Role.SUPER_ADMIN))))
                .andExpect(status().isForbidden());
    }

    private void insertUser(long userId, Role role) {
        jdbc.update("""
                insert into users (
                    id, email, password_hash, nickname, public_tag,
                    role, account_status, neighborhood_code
                ) values (?, ?, 'encoded', ?, ?, ?, 'ACTIVE', ?)
                """, userId, "dashboard" + userId + "@test.com", "관리자" + userId,
                "dashboard" + userId + "#" + String.format("%04d", userId),
                role.name(), NEIGHBORHOOD);
    }

    private static CurrentUser principal(long userId, Role role) {
        return new CurrentUser(userId, "dashboard" + userId + "@test.com", role);
    }
}
