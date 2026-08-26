package itda.petverification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.Pet;
import itda.pet.domain.PetSex;
import itda.pet.repository.PetRepository;
import itda.pet.service.PetCreateCommand;
import itda.pet.service.PetCreationTransactionService;
import itda.petverification.PetVerificationEvidence;
import itda.petverification.domain.PetVerification;
import itda.petverification.domain.PetVerificationDeviceType;
import itda.petverification.domain.PetVerificationProvider;
import itda.petverification.repository.PetVerificationRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class PetVerificationTransactionPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                        .asCompatibleSubstituteFor("postgres")
        );

    @Autowired private PetCreationTransactionService petCreationTransactions;
    @Autowired private PetVerificationApplyTransactionService verificationTransactions;
    @Autowired private PetRepository petRepository;
    @Autowired private PetVerificationRepository verificationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("update users set active_pet_id = null");
        jdbc.update("delete from pet_verifications");
        jdbc.update("delete from pets");
        jdbc.update("delete from refresh_tokens");
        jdbc.update("delete from users");
    }

    @Test
    void creationAttemptRollsBackItsPetWhenTheSameTransactionCannotInsertVerification() {
        User user = user();
        Pet existingPet = pet(user, "existing#A1B2");
        verificationRepository.saveAndFlush(PetVerification.create(existingPet, evidence("a".repeat(64)).toEntityEvidence()));

        assertConflict(() -> petCreationTransactions.createAttempt(user.getId(), command(), "new#C3D4",
                evidence("a".repeat(64))));

        assertThat(petRepository.countByOwner_IdAndDeletedAtIsNull(user.getId())).isEqualTo(1);
        assertThat(verificationRepository.count()).isEqualTo(1);
    }

    @Test
    void existingPetApplyMapsTheDatabaseWideRegistrationUniqueConstraintToConflict() {
        User user = user();
        Pet alreadyVerified = pet(user, "existing#A1B2");
        Pet target = pet(user, "target#C3D4");
        verificationRepository.saveAndFlush(PetVerification.create(alreadyVerified,
                evidence("b".repeat(64)).toEntityEvidence()));

        assertConflict(() -> verificationTransactions.apply(user.getId(), target.getId(), evidence("b".repeat(64))));

        assertThat(verificationRepository.findByPet_Id(target.getId())).isEmpty();
        assertThat(verificationRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentExistingPetAppliesWithDifferentEvidenceProduceOneSuccessOneConflictAndOneRow()
            throws Exception {
        User user = user();
        Pet target = pet(user, "target#A1B2");
        CountDownLatch bothTransactionsStarted = new CountDownLatch(2);
        CountDownLatch allowApply = new CountDownLatch(1);

        List<VerificationApplyOutcome> outcomes = runConcurrently(
                () -> applyInNewTransaction(user.getId(), target.getId(), evidence("a".repeat(64)),
                        bothTransactionsStarted, allowApply),
                () -> applyInNewTransaction(user.getId(), target.getId(), evidence("b".repeat(64)),
                        bothTransactionsStarted, allowApply),
                bothTransactionsStarted, allowApply
        );

        assertOneSuccessAndOneVerificationConflict(outcomes);
        assertThat(verificationRepository.count()).isEqualTo(1);
        assertThat(verificationRepository.findByPet_Id(target.getId())).isPresent();
    }

    @Test
    void concurrentDifferentPetsWithSameCanonicalHmacProduceOneSuccessOneConflictAndOneRow()
            throws Exception {
        User user = user();
        Pet firstPet = pet(user, "first#A1B2");
        Pet secondPet = pet(user, "second#C3D4");
        String sharedHmac = "c".repeat(64);
        CountDownLatch bothTransactionsStarted = new CountDownLatch(2);
        CountDownLatch allowApply = new CountDownLatch(1);

        List<VerificationApplyOutcome> outcomes = runConcurrently(
                () -> applyInNewTransaction(user.getId(), firstPet.getId(), evidence(sharedHmac),
                        bothTransactionsStarted, allowApply),
                () -> applyInNewTransaction(user.getId(), secondPet.getId(), evidence(sharedHmac),
                        bothTransactionsStarted, allowApply),
                bothTransactionsStarted, allowApply
        );

        assertOneSuccessAndOneVerificationConflict(outcomes);
        assertThat(verificationRepository.count()).isEqualTo(1);
    }

    private User user() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return userRepository.saveAndFlush(User.register(unique + "@example.test", "encoded", "Synthetic Owner",
                "owner#" + unique.substring(0, 8), "4113165000"));
    }

    private Pet pet(User user, String publicTag) {
        return petRepository.saveAndFlush(Pet.register(user, publicTag, "Synthetic Pet", null, null, null,
                null, null, null, null, null, null));
    }

    private PetCreateCommand command() {
        return new PetCreateCommand("New Pet", null, null, null, null, null,
                null, null, null, null);
    }

    private PetVerificationEvidence evidence(String hmac) {
        return new PetVerificationEvidence(PetVerificationProvider.ANIMAL_INFO_V3, hmac,
                PetVerificationDeviceType.IMPLANTED, "Synthetic Pet", LocalDate.of(2022, 1, 1),
                PetSex.FEMALE, "Synthetic Breed", true);
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.PET_VERIFICATION_CONFLICT);
    }

    private VerificationApplyOutcome applyInNewTransaction(
            Long userId,
            Long petId,
            PetVerificationEvidence evidence,
            CountDownLatch bothTransactionsStarted,
            CountDownLatch allowApply
    ) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                bothTransactionsStarted.countDown();
                await(allowApply);
                verificationTransactions.apply(userId, petId, evidence);
            });
            return VerificationApplyOutcome.success();
        } catch (BusinessException exception) {
            return VerificationApplyOutcome.failed(exception.getErrorCode());
        }
    }

    private List<VerificationApplyOutcome> runConcurrently(
            ConcurrentVerificationApply first,
            ConcurrentVerificationApply second,
            CountDownLatch bothTransactionsStarted,
            CountDownLatch allowApply
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<VerificationApplyOutcome>> futures = new ArrayList<>();
        try {
            for (ConcurrentVerificationApply action : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    return action.apply();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(bothTransactionsStarted.await(10, TimeUnit.SECONDS)).isTrue();
            allowApply.countDown();

            List<VerificationApplyOutcome> outcomes = new ArrayList<>();
            for (Future<VerificationApplyOutcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            allowApply.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertOneSuccessAndOneVerificationConflict(List<VerificationApplyOutcome> outcomes) {
        assertThat(outcomes.stream().filter(VerificationApplyOutcome::succeeded).count()).isEqualTo(1);
        assertThat(outcomes).extracting(VerificationApplyOutcome::errorCode)
                .containsExactlyInAnyOrder(null, ErrorCode.PET_VERIFICATION_CONFLICT);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for concurrent verification barrier");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    @FunctionalInterface
    private interface ConcurrentVerificationApply {
        VerificationApplyOutcome apply();
    }

    private record VerificationApplyOutcome(boolean succeeded, ErrorCode errorCode) {
        static VerificationApplyOutcome success() {
            return new VerificationApplyOutcome(true, null);
        }

        static VerificationApplyOutcome failed(ErrorCode errorCode) {
            return new VerificationApplyOutcome(false, errorCode);
        }
    }
}
