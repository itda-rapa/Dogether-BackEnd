package itda.block;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import itda.common.security.CurrentUser;
import itda.user.domain.Role;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class BlockApiContractPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean(name = "init")
    private CommandLineRunner adminBootstrapRunner;

    private long blockerUserId;
    private long blockerPetId;
    private long targetPetId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                truncate user_blocks, pets, users
                restart identity cascade
                """);

        blockerUserId = createUser("blocker");
        long targetUserId = createUser("target");
        blockerPetId = createPet(blockerUserId, "차단자펫");
        targetPetId = createPet(targetUserId, "대상펫");
        jdbcTemplate.update(
                "update users set active_pet_id = ? where id = ?",
                blockerPetId,
                blockerUserId
        );
    }

    @Test
    @DisplayName("신규 차단은 201, 같은 대상 재요청은 200과 동일 blockId를 반환한다")
    void createAndIdempotentRetryHaveDistinctStatuses() throws Exception {
        String request = """
                {"targetPetId":%d}
                """.formatted(targetPetId);

        String first = mockMvc.perform(post("/me/blocks")
                        .with(user(principal(blockerUserId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.blockId").isNumber())
                .andExpect(jsonPath("$.data.blockedUserId").isNumber())
                .andExpect(jsonPath("$.data.blockedUserPublicTag").isNotEmpty())
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andReturn().getResponse().getContentAsString();

        long blockId = ((Number) JsonPath.read(first, "$.data.blockId")).longValue();

        mockMvc.perform(post("/me/blocks")
                        .with(user(principal(blockerUserId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blockId").value((int) blockId));
    }

    @Test
    @DisplayName("인증 없이 차단 API를 호출하면 401을 반환한다")
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/me/blocks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("limit 범위 밖 값은 VALIDATION_FAILED로 거절한다")
    void invalidLimitIsRejected() throws Exception {
        mockMvc.perform(get("/me/blocks")
                        .param("limit", "101")
                        .with(user(principal(blockerUserId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("손상된 커서는 VALIDATION_FAILED로 거절한다")
    void malformedCursorIsRejected() throws Exception {
        mockMvc.perform(get("/me/blocks")
                        .param("cursor", "not-a-valid-cursor")
                        .with(user(principal(blockerUserId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private CurrentUser principal(long userId) {
        return new CurrentUser(userId, "user" + userId + "@test.com", Role.USER);
    }

    private long createUser(String label) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return jdbcTemplate.queryForObject("""
                insert into users (
                    email, password_hash, nickname, public_tag,
                    role, account_status, neighborhood_code
                ) values (?, 'encoded', ?, ?, 'USER', 'ACTIVE', '4113111500')
                returning id
                """,
                Long.class,
                unique + "@example.com",
                label,
                label + "#" + unique.substring(0, 8));
    }

    private long createPet(long ownerUserId, String nickname) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return jdbcTemplate.queryForObject("""
                insert into pets (owner_user_id, public_tag, nickname, status)
                values (?, ?, ?, 'ACTIVE')
                returning id
                """,
                Long.class,
                ownerUserId,
                nickname + "#" + unique.substring(0, 4).toUpperCase(),
                nickname);
    }
}
