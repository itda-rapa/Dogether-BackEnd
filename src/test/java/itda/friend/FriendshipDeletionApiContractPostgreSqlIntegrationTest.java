package itda.friend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.common.security.CurrentUser;
import itda.user.domain.Role;
import java.util.UUID;
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
import org.testcontainers.utility.DockerImageName;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class FriendshipDeletionApiContractPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer(
                DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                        .asCompatibleSubstituteFor("postgres")
        );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long sourceUserId;
    private Long targetUserId;
    private Long sourcePetId;
    private Long targetPetId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE chat_messages, chat_room_participants, chat_rooms,
                    greetings, setlog_reactions, setlogs, media, user_blocks,
                    friendships, friend_requests, pets, users
                RESTART IDENTITY CASCADE
                """);
        sourceUserId = createUser("source");
        targetUserId = createUser("target");
        sourcePetId = createPet(sourceUserId, "source", "ACTIVE");
        targetPetId = createPet(targetUserId, "target", "ACTIVE");
        setActivePet(sourceUserId, sourcePetId);
        setActivePet(targetUserId, targetPetId);
    }

    @Test
    void deletesFriendshipWithEmpty204AndReturns404OnRetry() throws Exception {
        insertFriendship(sourcePetId, targetPetId);

        mockMvc.perform(delete(
                        "/pets/{petId}/friends/{friendPetId}",
                        sourcePetId,
                        targetPetId
                ).with(user(principal(sourceUserId))))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        mockMvc.perform(delete(
                        "/pets/{petId}/friends/{friendPetId}",
                        sourcePetId,
                        targetPetId
                ).with(user(principal(sourceUserId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code")
                        .value("FRIENDSHIP_NOT_FOUND"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        insertFriendship(sourcePetId, targetPetId);

        mockMvc.perform(delete(
                        "/pets/{petId}/friends/{friendPetId}",
                        sourcePetId,
                        targetPetId
                ))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void returnsPetNotFoundForMissingSource() throws Exception {
        mockMvc.perform(delete(
                        "/pets/{petId}/friends/{friendPetId}",
                        Long.MAX_VALUE,
                        targetPetId
                ).with(user(principal(sourceUserId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PET_NOT_FOUND"));
    }

    @Test
    void returnsPetNotFoundForDeletedSourceBeforeOwnership() throws Exception {
        jdbcTemplate.update("""
                UPDATE pets
                   SET status = 'DELETED',
                       deleted_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, sourcePetId);

        mockMvc.perform(delete(
                        "/pets/{petId}/friends/{friendPetId}",
                        sourcePetId,
                        targetPetId
                ).with(user(principal(targetUserId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PET_NOT_FOUND"));
    }

    @Test
    void returnsPetNotOwnedForUndeletedSourceOwnedByAnotherUser()
            throws Exception {
        mockMvc.perform(delete(
                        "/pets/{petId}/friends/{friendPetId}",
                        targetPetId,
                        sourcePetId
                ).with(user(principal(sourceUserId))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PET_NOT_OWNED"));
    }

    @Test
    void allowsOwnedPetThatIsNotCurrentActivePet() throws Exception {
        Long nonActivePetId = createPet(sourceUserId, "nonActive", "ACTIVE");
        insertFriendship(nonActivePetId, targetPetId);

        mockMvc.perform(delete(
                        "/pets/{petId}/friends/{friendPetId}",
                        nonActivePetId,
                        targetPetId
                ).with(user(principal(sourceUserId))))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void allowsOwnedSuspendedSourcePet() throws Exception {
        jdbcTemplate.update(
                "UPDATE pets SET status = 'SUSPENDED' WHERE id = ?",
                sourcePetId
        );
        insertFriendship(sourcePetId, targetPetId);

        mockMvc.perform(delete(
                        "/pets/{petId}/friends/{friendPetId}",
                        sourcePetId,
                        targetPetId
                ).with(user(principal(sourceUserId))))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    private CurrentUser principal(Long userId) {
        return new CurrentUser(userId, "user@example.com", Role.USER);
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
                ) VALUES (?, 'encoded', ?, ?, 'USER', 'ACTIVE', '4113165000')
                RETURNING id
                """,
                Long.class,
                prefix + unique + "@example.com",
                prefix,
                prefix + "#" + unique.substring(0, 8)
        );
    }

    private Long createPet(Long ownerUserId, String prefix, String status) {
        String unique = unique();
        return jdbcTemplate.queryForObject("""
                INSERT INTO pets (
                    owner_user_id,
                    public_tag,
                    nickname,
                    status
                ) VALUES (?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                ownerUserId,
                prefix + "#" + unique.substring(0, 4).toUpperCase(),
                prefix,
                status
        );
    }

    private void setActivePet(Long userId, Long petId) {
        jdbcTemplate.update(
                "UPDATE users SET active_pet_id = ? WHERE id = ?",
                petId,
                userId
        );
    }

    private void insertFriendship(Long firstPetId, Long secondPetId) {
        jdbcTemplate.update("""
                INSERT INTO friendships (pet_low_id, pet_high_id)
                VALUES (?, ?)
                """,
                Math.min(firstPetId, secondPetId),
                Math.max(firstPetId, secondPetId)
        );
    }

    private String unique() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
