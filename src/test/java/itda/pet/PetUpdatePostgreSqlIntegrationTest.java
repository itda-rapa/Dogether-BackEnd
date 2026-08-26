package itda.pet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.common.security.CurrentUser;
import itda.user.domain.Role;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;
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
class PetUpdatePostgreSqlIntegrationTest {

    private static final OffsetDateTime PAST_UPDATED_AT =
            OffsetDateTime.of(
                    2026,
                    1,
                    1,
                    0,
                    0,
                    0,
                    0,
                    ZoneOffset.UTC
            );

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

    @Autowired
    private EntityManager entityManager;

    private Long ownerUserId;
    private Long petId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                truncate user_blocks, friendships, friend_requests, pets, users
                restart identity cascade
                """);
        ownerUserId = createUser("owner");
        petId = createPet(ownerUserId, "몽이#A7K2", "ACTIVE");
    }

    @Test
    void updatesFieldsAndExposesCommittedStateThroughDetailAndList()
            throws Exception {
        jdbcTemplate.update(
                "update users set active_pet_id = ? where id = ?",
                petId,
                ownerUserId
        );
        jdbcTemplate.update(
                "update pets set updated_at = ? where id = ?",
                PAST_UPDATED_AT,
                petId
        );
        Map<String, Object> before = petState(petId);
        entityManager.clear();

        update(ownerUserId, petId, """
                {
                  "nickname": "  초코  ",
                  "breedName": null,
                  "sex": null,
                  "neutered": null,
                  "birthDate": null,
                  "weightKg": 4.2,
                  "sizeCode": null,
                  "bio": "   ",
                  "personalityTags": [],
                  "careNote": null
                }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.nickname").value("초코"))
                .andExpect(jsonPath("$.data.breedName").isEmpty())
                .andExpect(jsonPath("$.data.sex").isEmpty())
                .andExpect(jsonPath("$.data.neutered").isEmpty())
                .andExpect(jsonPath("$.data.birthDate").isEmpty())
                .andExpect(jsonPath("$.data.weightKg").value(4.2))
                .andExpect(jsonPath("$.data.sizeCode").isEmpty())
                .andExpect(jsonPath("$.data.bio").value("   "))
                .andExpect(jsonPath("$.data.personalityTags").isEmpty())
                .andExpect(jsonPath("$.data.careNote").isEmpty())
                .andExpect(jsonPath("$.data.publicTag").value("몽이#A7K2"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.verified").value(false))
                .andExpect(jsonPath("$.data.verifiedAt").isEmpty());

        entityManager.clear();
        Map<String, Object> after = petState(petId);
        assertThat(after.get("nickname")).isEqualTo("초코");
        assertThat(after.get("breed_name")).isNull();
        assertThat(after.get("sex")).isNull();
        assertThat(after.get("neutered")).isNull();
        assertThat(after.get("birth_date")).isNull();
        assertThat((BigDecimal) after.get("weight_kg"))
                .isEqualByComparingTo("4.20");
        assertThat(after.get("size_code")).isNull();
        assertThat(after.get("bio")).isEqualTo("   ");
        assertThat(after.get("personality_tags").toString()).isEqualTo("[]");
        assertThat(after.get("care_note")).isNull();
        assertThat(after.get("version"))
                .isEqualTo(((Long) before.get("version")) + 1);
        assertThat(((Timestamp) after.get("updated_at")).toInstant())
                .isAfter(PAST_UPDATED_AT.toInstant());
        assertInvariants(before, after);

        mockMvc.perform(get("/pets/{petId}", petId)
                        .with(user(principal(ownerUserId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("초코"))
                .andExpect(jsonPath("$.data.personalityTags").isEmpty());
        mockMvc.perform(get("/pets/me")
                        .with(user(principal(ownerUserId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].petId").value(petId))
                .andExpect(jsonPath("$.data[0].nickname").value("초코"))
                .andExpect(jsonPath("$.data[0].active").value(true));
    }

    @Test
    void noOpKeepsVersionAndUpdatedAtIncludingDecimalScaleDifference()
            throws Exception {
        jdbcTemplate.update(
                "update pets set updated_at = ? where id = ?",
                PAST_UPDATED_AT,
                petId
        );
        Map<String, Object> before = petState(petId);
        entityManager.clear();

        update(ownerUserId, petId, """
                {
                  "nickname": "몽이",
                  "breedName": "말티즈",
                  "sex": "FEMALE",
                  "neutered": true,
                  "birthDate": "2020-01-02",
                  "weightKg": 3.4,
                  "sizeCode": "SMALL",
                  "bio": "소개",
                  "personalityTags": ["친화적", "활발함"],
                  "careNote": "돌봄 메모"
                }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("몽이"));

        entityManager.clear();
        Map<String, Object> after = petState(petId);
        assertThat(after.get("version")).isEqualTo(before.get("version"));
        assertThat(after.get("updated_at")).isEqualTo(before.get("updated_at"));
        assertThat(after).isEqualTo(before);
    }

    @Test
    void supportsNullValueTransitionsAndJsonbEmptyArray() throws Exception {
        update(ownerUserId, petId, """
                {
                  "bio": null,
                  "personalityTags": []
                }
                """).andExpect(status().isOk());
        assertThat(petState(petId).get("bio")).isNull();
        assertThat(petState(petId).get("personality_tags").toString())
                .isEqualTo("[]");

        update(ownerUserId, petId, """
                {
                  "bio": "다시 설정",
                  "personalityTags": ["차분함"]
                }
                """).andExpect(status().isOk());
        assertThat(petState(petId).get("bio")).isEqualTo("다시 설정");
        assertThat(petState(petId).get("personality_tags").toString())
                .isEqualTo("[\"차분함\"]");

        update(ownerUserId, petId, "{\"bio\":null}")
                .andExpect(status().isOk());
        assertThat(petState(petId).get("bio")).isNull();
    }

    @Test
    void updatesSuspendedNonActiveOwnedPet() throws Exception {
        Long activePetId = createPet(ownerUserId, "보리#B8M3", "ACTIVE");
        jdbcTemplate.update(
                "update users set active_pet_id = ? where id = ?",
                activePetId,
                ownerUserId
        );
        jdbcTemplate.update(
                "update pets set status = 'SUSPENDED' where id = ?",
                petId
        );

        update(ownerUserId, petId, "{\"nickname\":\"초코\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.nickname").value("초코"));
    }

    @Test
    void validationAndOwnershipFailuresDoNotPartiallyUpdate() throws Exception {
        Map<String, Object> original = petState(petId);
        Long otherUserId = createUser("other");

        update(otherUserId, petId, "{\"nickname\":\"탈취\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PET_NOT_OWNED"));
        assertThat(petState(petId)).isEqualTo(original);

        update(ownerUserId, petId, """
                {
                  "nickname": "변경",
                  "weightKg": "1.25"
                }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("VALIDATION_FAILED"));
        assertThat(petState(petId)).isEqualTo(original);
    }

    @Test
    void deletedPetIsNotFoundBeforeOwnership() throws Exception {
        Long otherUserId = createUser("other");
        jdbcTemplate.update("""
                update pets
                   set status = 'DELETED',
                       deleted_at = CURRENT_TIMESTAMP
                 where id = ?
                """, petId);

        update(otherUserId, petId, "{\"nickname\":\"탈취\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PET_NOT_FOUND"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(patch("/pets/{petId}", petId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"초코\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    private ResultActions update(Long userId, Long targetPetId, String body)
            throws Exception {
        return mockMvc.perform(patch("/pets/{petId}", targetPetId)
                .with(user(principal(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private CurrentUser principal(Long userId) {
        return new CurrentUser(
                userId,
                "user" + userId + "@example.com",
                Role.USER
        );
    }

    private Long createUser(String label) {
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
                ) values (?, 'encoded', ?, ?, 'USER', 'ACTIVE', '4113165000')
                returning id
                """,
                Long.class,
                label + unique + "@example.com",
                label,
                label + "#" + unique.substring(0, 8)
        );
    }

    private Long createPet(
            Long ownerId,
            String publicTag,
            String status
    ) {
        return jdbcTemplate.queryForObject("""
                insert into pets (
                    owner_user_id,
                    public_tag,
                    nickname,
                    breed_name,
                    sex,
                    neutered,
                    birth_date,
                    weight_kg,
                    size_code,
                    bio,
                    personality_tags,
                    care_note,
                    status
                ) values (
                    ?, ?, '몽이', '말티즈', 'FEMALE', true,
                    ?, 3.40, 'SMALL', '소개',
                    cast(? as jsonb), '돌봄 메모', ?
                )
                returning id
                """,
                Long.class,
                ownerId,
                publicTag,
                LocalDate.of(2020, 1, 2),
                "[\"친화적\",\"활발함\"]",
                status
        );
    }

    private Map<String, Object> petState(Long targetPetId) {
        return jdbcTemplate.queryForMap("""
                select owner_user_id,
                       public_tag,
                       nickname,
                       breed_name,
                       sex,
                       neutered,
                       birth_date,
                       weight_kg,
                       size_code,
                       bio,
                       personality_tags,
                       care_note,
                       status,
                       profile_asset_id,
                       version,
                       updated_at,
                       deleted_at
                  from pets
                 where id = ?
                """, targetPetId);
    }

    private void assertInvariants(
            Map<String, Object> before,
            Map<String, Object> after
    ) {
        List<String> invariantFields = List.of(
                "owner_user_id",
                "public_tag",
                "status",
                "profile_asset_id",
                "deleted_at"
        );
        for (String field : invariantFields) {
            assertThat(after.get(field)).isEqualTo(before.get(field));
        }
    }
}
