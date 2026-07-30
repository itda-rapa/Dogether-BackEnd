package itda.friend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.common.security.CurrentUser;
import itda.user.domain.Role;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
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
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class FriendRequestApiContractPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long sourceUserId;
    private Long sourcePetId;
    private Long targetPetId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE chat_room_participants, chat_rooms, user_blocks,
                    friendships, friend_requests, pets, users
                RESTART IDENTITY CASCADE
                """);
        sourceUserId = createUser("source");
        Long targetUserId = createUser("target");
        sourcePetId = createPet(sourceUserId, "sourcePet");
        targetPetId = createPet(targetUserId, "targetPet");
        jdbcTemplate.update(
                "UPDATE users SET active_pet_id = ? WHERE id = ?",
                sourcePetId,
                sourceUserId
        );
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/friend-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void rejectsMissingTargetPetId() throws Exception {
        mockMvc.perform(post("/friend-requests")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("VALIDATION_FAILED"));
    }

    @Test
    void createsPendingWith201Contract() throws Exception {
        mockMvc.perform(post("/friend-requests")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.requesterPet.petId")
                        .value(sourcePetId))
                .andExpect(jsonPath("$.data.targetPet.petId")
                        .value(targetPetId))
                .andExpect(jsonPath("$.data.requesterPet.relationship")
                        .value("NONE"))
                .andExpect(jsonPath("$.data.targetPet.relationship")
                        .value("REQUEST_SENT"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.respondedAt").isEmpty())
                .andExpect(jsonPath("$.data.directRoomId").isEmpty());
    }

    @Test
    void autoAcceptsReversePendingWith200Contract() throws Exception {
        Long requestId = jdbcTemplate.queryForObject("""
                INSERT INTO friend_requests (
                    requester_pet_id,
                    target_pet_id,
                    status,
                    expires_at
                ) VALUES (?, ?, 'PENDING', ?)
                RETURNING id
                """,
                Long.class,
                targetPetId,
                sourcePetId,
                Instant.now().plusSeconds(3600).atOffset(ZoneOffset.UTC)
        );

        mockMvc.perform(post("/friend-requests")
                        .with(user(principal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestId").value(requestId))
                .andExpect(jsonPath("$.data.requesterPet.petId")
                        .value(targetPetId))
                .andExpect(jsonPath("$.data.targetPet.petId")
                        .value(sourcePetId))
                .andExpect(jsonPath("$.data.requesterPet.relationship")
                        .value("FRIEND"))
                .andExpect(jsonPath("$.data.targetPet.relationship")
                        .value("NONE"))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.respondedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.directRoomId").isNumber());
    }

    private CurrentUser principal() {
        return new CurrentUser(
                sourceUserId,
                "source@example.com",
                Role.USER
        );
    }

    private String requestBody() {
        return """
                {"targetPetId":%d}
                """.formatted(targetPetId);
    }

    private Long createUser(String prefix) {
        String unique = unique();
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (
                    email,
                    password_hash,
                    nickname,
                    public_tag,
                    role,
                    account_status,
                    neighborhood_code
                ) VALUES (?, 'encoded', ?, ?, 'USER', 'ACTIVE', '4113111500')
                RETURNING id
                """,
                Long.class,
                prefix + unique + "@example.com",
                prefix,
                prefix + "#" + unique.substring(0, 8)
        );
    }

    private Long createPet(Long ownerUserId, String prefix) {
        String unique = unique();
        return jdbcTemplate.queryForObject("""
                INSERT INTO pets (
                    owner_user_id,
                    public_tag,
                    nickname,
                    status
                ) VALUES (?, ?, ?, 'ACTIVE')
                RETURNING id
                """,
                Long.class,
                ownerUserId,
                prefix + "#" + unique.substring(0, 4).toUpperCase(),
                prefix
        );
    }

    private String unique() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
