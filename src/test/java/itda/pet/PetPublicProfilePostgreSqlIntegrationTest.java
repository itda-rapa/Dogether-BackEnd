package itda.pet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.ResultActions;
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
class PetPublicProfilePostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long viewerUserId;
    private Long targetOwnerId;
    private Long targetPetId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                truncate user_blocks, friendships, friend_requests, pets, users
                restart identity cascade
                """);
        viewerUserId = createUser("viewer", "ACTIVE");
        targetOwnerId = createUser("target", "ACTIVE");
        targetPetId = createPet(targetOwnerId, "몽이#A7K2", "ACTIVE");
    }

    @Test
    void returnsPublicProfileForPersistedVisiblePet() throws Exception {
        publicProfile(targetPetId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("Pet 공개 프로필이 조회되었습니다."))
                .andExpect(jsonPath("$.data.petId").value(targetPetId))
                .andExpect(jsonPath("$.data.publicTag").value("몽이#A7K2"))
                .andExpect(jsonPath("$.data.relationship").isEmpty());
    }

    @Test
    void hidesPersistedNonPublicPetAndOwnerStates() throws Exception {
        Long suspendedPetId = createPet(targetOwnerId, "보리#B8M3", "SUSPENDED");
        Long deletedPetId = createPet(targetOwnerId, "초코#C9N4", "ACTIVE");
        jdbcTemplate.update("""
                update pets
                   set status = 'DELETED', deleted_at = CURRENT_TIMESTAMP
                 where id = ?
                """,
                deletedPetId
        );

        Long suspendedOwnerId = createUser("suspended", "SUSPENDED");
        Long suspendedOwnerPetId = createPet(
                suspendedOwnerId,
                "마루#E3Q6",
                "ACTIVE"
        );
        Long withdrawnOwnerId = createUser("withdrawn", "ACTIVE");
        jdbcTemplate.update("""
                update users
                   set account_status = 'WITHDRAWN',
                       withdrawn_at = CURRENT_TIMESTAMP
                 where id = ?
                """,
                withdrawnOwnerId
        );
        Long withdrawnOwnerPetId = createPet(
                withdrawnOwnerId,
                "나무#F4R7",
                "ACTIVE"
        );

        assertNotFound(suspendedPetId);
        assertNotFound(deletedPetId);
        assertNotFound(suspendedOwnerPetId);
        assertNotFound(withdrawnOwnerPetId);
        assertThat(targetPetId).isNotNull();
    }

    private void assertNotFound(Long petId) throws Exception {
        publicProfile(petId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PET_NOT_FOUND"));
    }

    private ResultActions publicProfile(Long petId) throws Exception {
        return mockMvc.perform(get("/pets/{petId}/profile", petId)
                .with(user(new CurrentUser(
                        viewerUserId,
                        "viewer@example.com",
                        Role.USER
                ))));
    }

    private Long createUser(String label, String accountStatus) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return jdbcTemplate.queryForObject("""
                insert into users (
                    email,
                    password_hash,
                    nickname,
                    public_tag,
                    role,
                    account_status,
                    neighborhood_code
                ) values (?, 'encoded', ?, ?, 'USER', ?, '4113111500')
                returning id
                """,
                Long.class,
                label + unique + "@example.com",
                label,
                label + "#" + unique.substring(0, 8),
                accountStatus
        );
    }

    private Long createPet(
            Long ownerUserId,
            String publicTag,
            String status
    ) {
        return jdbcTemplate.queryForObject("""
                insert into pets (
                    owner_user_id,
                    public_tag,
                    nickname,
                    status
                ) values (?, ?, '몽이', ?)
                returning id
                """,
                Long.class,
                ownerUserId,
                publicTag,
                status
        );
    }
}
