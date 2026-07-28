package itda.pet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.Pet;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
@DisplayName("PetCreation PostgreSQL Integration")
class PetCreationPostgreSqlIntegrationTest {

    private static final String DUPLICATE_TAG = "중복#A7K2";
    private static final String NEW_TAG = "신규#B8M3";

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
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private PetPublicTagGenerator petPublicTagGenerator;

    @BeforeEach
    void cleanDatabase() {
        reset(petPublicTagGenerator);
        jdbcTemplate.update("update users set active_pet_id = null");
        jdbcTemplate.update("delete from pets");
        jdbcTemplate.update("delete from refresh_tokens");
        jdbcTemplate.update("delete from users");
    }

    @Test
    @DisplayName("It: PublicTag 충돌 Transaction을 Rollback하고 새 Transaction으로 재시도한다")
    void retriesPublicTagConflictInNewTransaction() {
        User user = createUser();
        savePet(user, DUPLICATE_TAG, "기존");
        PetCreateCommand command = command();
        given(petPublicTagGenerator.generate(command.nickname()))
                .willReturn(DUPLICATE_TAG, NEW_TAG);

        PetCreationOutcome outcome = petCreationService.create(
                user.getId(),
                command
        );

        assertThat(outcome.firstPetCandidate()).isFalse();
        assertThat(petRepository.countByOwner_IdAndDeletedAtIsNull(user.getId()))
                .isEqualTo(2);
        Pet savedPet = petRepository.findById(outcome.petId()).orElseThrow();
        assertThat(savedPet.getPublicTag()).isEqualTo(NEW_TAG);
        assertThat(petRepository.findAll())
                .extracting(Pet::getPublicTag)
                .containsExactlyInAnyOrder(DUPLICATE_TAG, NEW_TAG);
        then(petPublicTagGenerator).should(times(2))
                .generate(command.nickname());
    }

    @Test
    @DisplayName("It: 활성 Transaction 안에서는 Propagation.NEVER로 생성하지 않는다")
    void rejectsCreationInsideExistingTransaction() {
        User user = createUser();
        PetCreateCommand command = command();

        assertThatThrownBy(() ->
                new TransactionTemplate(transactionManager).executeWithoutResult(
                        status -> petCreationService.create(user.getId(), command)
                )
        ).isInstanceOf(IllegalTransactionStateException.class);

        assertThat(petRepository.countByOwner_IdAndDeletedAtIsNull(user.getId()))
                .isZero();
        then(petPublicTagGenerator).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("It: 미삭제 Pet이 5마리면 생성하지 않고 한도를 반환한다")
    void rejectsWhenUndeletedPetLimitIsReached() {
        User user = createUser();
        for (int index = 0; index < 5; index++) {
            savePet(user, "기존" + index + "#A7K" + index, "기존" + index);
        }
        PetCreateCommand command = command();
        given(petPublicTagGenerator.generate(command.nickname()))
                .willReturn(NEW_TAG);

        assertErrorCode(
                () -> petCreationService.create(user.getId(), command),
                ErrorCode.PET_LIMIT_EXCEEDED
        );

        assertThat(petRepository.countByOwner_IdAndDeletedAtIsNull(user.getId()))
                .isEqualTo(5);
        then(petPublicTagGenerator).should().generate(command.nickname());
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

    private void savePet(User user, String publicTag, String nickname) {
        petRepository.saveAndFlush(Pet.register(
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

    private void assertErrorCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            ErrorCode errorCode
    ) {
        assertThatThrownBy(operation)
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).getErrorCode()
                )
                .isEqualTo(errorCode);
    }
}
