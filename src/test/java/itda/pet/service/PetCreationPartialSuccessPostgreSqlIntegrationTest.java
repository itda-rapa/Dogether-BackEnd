package itda.pet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.reset;

import itda.pet.repository.PetRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
@DisplayName("PetCreation 부분 성공 PostgreSQL Integration")
class PetCreationPartialSuccessPostgreSqlIntegrationTest {

    private static final String PUBLIC_TAG = "신규#A7K2";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private PetCreationService petCreationService;

    @Autowired
    private PetRepository petRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private PetPublicTagGenerator petPublicTagGenerator;

    @MockitoBean
    private ActivePetAssignmentTransactionService
            activePetAssignmentTransactionService;

    @BeforeEach
    void cleanDatabase() {
        reset(
                petPublicTagGenerator,
                activePetAssignmentTransactionService
        );
        jdbcTemplate.update("update users set active_pet_id = null");
        jdbcTemplate.update("delete from pets");
        jdbcTemplate.update("delete from refresh_tokens");
        jdbcTemplate.update("delete from users");
    }

    @Test
    @DisplayName("It: PostgreSQL Pet Commit 뒤 Mock 자동 지정의 비관적 잠금 실패를 RETRY_REQUIRED로 처리한다")
    void keepsCreatedPetWhenAutomaticAssignmentHasLockFailure() {
        User user = createUser();
        PetCreateCommand command = command();
        given(petPublicTagGenerator.generate(command.nickname()))
                .willReturn(PUBLIC_TAG);
        given(activePetAssignmentTransactionService.assignIfAbsent(
                anyLong(),
                anyLong()
        )).willThrow(new PessimisticLockingFailureException(
                "automatic assignment lock failure"
        ));

        PetCreationResult result = petCreationService.create(
                user.getId(),
                command
        );

        assertThat(result.activePetAssignmentStatus())
                .isEqualTo(ActivePetAssignmentStatus.RETRY_REQUIRED);
        assertThat(petRepository.findById(result.petId())).isPresent();
        assertThat(petRepository.countByOwner_IdAndDeletedAtIsNull(
                user.getId()
        )).isEqualTo(1);
        User persistedUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(persistedUser.getActivePetId()).isNull();
        then(activePetAssignmentTransactionService).should().assignIfAbsent(
                user.getId(),
                result.petId()
        );
    }

    private User createUser() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return userRepository.saveAndFlush(User.register(
                unique + "@example.com",
                "encoded",
                "보호자",
                "보호자#" + unique.substring(0, 8),
                "4113111500"
        ));
    }

    private PetCreateCommand command() {
        return new PetCreateCommand(
                "새 반려견",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
