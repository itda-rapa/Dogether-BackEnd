package itda.pet.service;

import static org.assertj.core.api.Assertions.assertThat;

import itda.pet.domain.Pet;
import itda.pet.domain.PetStatus;
import itda.pet.dto.PetResponse;
import itda.pet.repository.PetRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
@DisplayName("MyPet 목록 PostgreSQL Integration")
class MyPetListPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer(
                DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                        .asCompatibleSubstituteFor("postgres")
        );

    @Autowired
    private MyPetQueryService myPetQueryService;

    @Autowired
    private PetRepository petRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("update users set active_pet_id = null");
        jdbcTemplate.update("delete from pets");
        jdbcTemplate.update("delete from refresh_tokens");
        jdbcTemplate.update("delete from users");
        entityManager.clear();
    }

    @Test
    @DisplayName("It: Active Pet을 먼저 반환하고 미삭제 SUSPENDED Pet을 포함한다")
    void listsMyUndeletedPetsInRequiredOrder() {
        User user = createUser();
        Pet petA = savePet(user, "A#A7K2", "A");
        Pet petB = savePet(user, "B#B8M3", "B");
        Pet petC = savePet(user, "C#C9N4", "C");
        Pet petD = savePet(user, "D#D2P5", "D");
        Pet petE = savePet(user, "E#E3Q6", "E");

        user.selectActivePet(petB.getId());
        userRepository.saveAndFlush(user);
        jdbcTemplate.update(
                "update pets set status = 'SUSPENDED' where id = ?",
                petB.getId()
        );
        jdbcTemplate.update("""
                update pets
                   set status = 'DELETED',
                       deleted_at = ?
                 where id = ?
                """,
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                petD.getId()
        );
        setCreatedAt(petA, "2026-01-02T00:00:00Z");
        setCreatedAt(petC, "2026-01-03T00:00:00Z");
        setCreatedAt(petE, "2026-01-03T00:00:00Z");
        entityManager.clear();

        List<PetResponse> responses = myPetQueryService.getMyPets(
                user.getId()
        );

        assertThat(responses)
                .extracting(PetResponse::petId)
                .containsExactly(
                        petB.getId(),
                        petA.getId(),
                        petC.getId(),
                        petE.getId()
                );
        assertThat(responses.get(0).status()).isEqualTo(PetStatus.SUSPENDED);
        assertThat(responses.get(0).active()).isTrue();
        assertThat(responses.subList(1, responses.size()))
                .allSatisfy(response ->
                        assertThat(response.active()).isFalse()
                );
    }

    private User createUser() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return userRepository.saveAndFlush(User.register(
                unique + "@example.com",
                "encoded",
                "보호자",
                "보호자#" + unique.substring(0, 8),
                "4113165000"
        ));
    }

    private Pet savePet(User user, String publicTag, String nickname) {
        return petRepository.saveAndFlush(Pet.register(
                user,
                publicTag,
                nickname,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));
    }

    private void setCreatedAt(Pet pet, String createdAt) {
        jdbcTemplate.update(
                "update pets set created_at = ?, updated_at = ? where id = ?",
                Timestamp.from(Instant.parse(createdAt)),
                Timestamp.from(Instant.parse(createdAt)),
                pet.getId()
        );
    }
}
