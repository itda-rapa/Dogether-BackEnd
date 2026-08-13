package itda.petverification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.PetSex;
import itda.pet.service.ActivePetAssignmentTransactionService;
import itda.pet.service.ActivePetAssignmentStatus;
import itda.pet.service.PetCreateCommand;
import itda.pet.service.PetCreationOutcome;
import itda.pet.service.PetCreationService;
import itda.pet.service.PetCreationTransactionService;
import itda.pet.service.PetPublicTagGenerator;
import itda.petverification.domain.PetVerificationDeviceType;
import itda.petverification.domain.PetVerificationProvider;
import java.sql.SQLException;
import java.time.LocalDate;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class PetCreationVerificationEvidenceTest {

    @Mock private PetPublicTagGenerator tags;
    @Mock private PetCreationTransactionService transactions;
    @Mock private ActivePetAssignmentTransactionService activeAssignments;
    @Mock private PetVerificationRedisStore redis;

    private PetCreationService service;

    @BeforeEach
    void setUp() { service = new PetCreationService(tags, transactions, activeAssignments, redis); }

    @Test
    void nullTokenKeepsTheLegacyCreationPathWithoutTouchingRedis() {
        PetCreateCommand command = command();
        given(tags.generate(command.nickname())).willReturn("테스트견#A1B2");
        given(transactions.createAttempt(7L, command, "테스트견#A1B2"))
                .willReturn(new PetCreationOutcome(31L, false));

        service.create(7L, command, null);

        then(redis).shouldHaveNoInteractions();
        then(transactions).should().createAttempt(7L, command, "테스트견#A1B2");
    }

    @Test
    void emptyAsciiAndUnicodeWhitespaceTokensAreRejectedBeforeRedisReserve() {
        for (String blankToken : java.util.List.of("", " \t\n", "\u3000")) {
            assertThatThrownBy(() -> service.create(7L, command(), blankToken))
                    .isInstanceOf(BusinessException.class)
                    .extracting(error -> ((BusinessException) error).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        then(redis).shouldHaveNoInteractions();
        then(transactions).shouldHaveNoInteractions();
    }

    @Test
    void boundaryWhitespaceTokenIsPassedToRedisWithoutTrimming() {
        PetCreateCommand command = command();
        String rawToken = " token-with-boundary-space ";
        var reservation = new PetVerificationRedisStore.Reservation("server-reservation", evidence());
        given(redis.reserve(rawToken, 7L, PetVerificationFlowType.PET_CREATE, null)).willReturn(reservation);
        given(tags.generate(command.nickname())).willReturn("테스트견#A1B2");
        given(transactions.createAttempt(7L, command, "테스트견#A1B2", reservation.evidence()))
                .willReturn(new PetCreationOutcome(31L, false));

        service.create(7L, command, rawToken);

        then(redis).should().reserve(rawToken, 7L, PetVerificationFlowType.PET_CREATE, null);
        then(redis).should().finalize(rawToken, "server-reservation");
    }

    @Test
    void reservesOnceAcrossPublicTagRetryThenFinalizesAfterTheSingleCommittedCreation() {
        PetVerificationEvidence evidence = new PetVerificationEvidence(PetVerificationProvider.ANIMAL_INFO_V3,
                "a".repeat(64), PetVerificationDeviceType.IMPLANTED, "테스트견", LocalDate.of(2022, 1, 1),
                PetSex.FEMALE, "테스트품종", true);
        var reservation = new PetVerificationRedisStore.Reservation("server-reservation", evidence);
        PetCreateCommand command = new PetCreateCommand("테스트견", null, null, null, null, null,
                null, null, null, null);
        given(redis.reserve("synthetic-token", 7L, PetVerificationFlowType.PET_CREATE, null))
                .willReturn(reservation);
        given(tags.generate("테스트견")).willReturn("테스트견#A1B2", "테스트견#C3D4");
        given(transactions.createAttempt(eq(7L), eq(command), anyString(), eq(evidence)))
                .willThrow(publicTagConflict()).willReturn(new PetCreationOutcome(31L, false));

        service.create(7L, command, "synthetic-token");

        then(redis).should(times(1)).reserve("synthetic-token", 7L, PetVerificationFlowType.PET_CREATE, null);
        then(transactions).should(times(2)).createAttempt(eq(7L), eq(command), anyString(), eq(evidence));
        then(redis).should().finalize("synthetic-token", "server-reservation");
        then(redis).should(never()).release("synthetic-token", "server-reservation");
        then(activeAssignments).shouldHaveNoInteractions();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(redis, transactions);
        order.verify(redis).reserve("synthetic-token", 7L, PetVerificationFlowType.PET_CREATE, null);
        order.verify(transactions).createAttempt(7L, command, "테스트견#A1B2", evidence);
        order.verify(transactions).createAttempt(7L, command, "테스트견#C3D4", evidence);
        order.verify(redis).finalize("synthetic-token", "server-reservation");
    }

    @Test
    void verificationUniqueConflictIsNotRetriedAsAPublicTagCollisionAndReleasesTheReservation() {
        PetVerificationEvidence evidence = evidence();
        var reservation = new PetVerificationRedisStore.Reservation("server-reservation", evidence);
        PetCreateCommand command = new PetCreateCommand("테스트견", null, null, null, null, null,
                null, null, null, null);
        DataIntegrityViolationException verificationUnique = new DataIntegrityViolationException("duplicate verification",
                new ConstraintViolationException("duplicate", new SQLException(), "insert",
                        "uk_pet_verifications_registration_number_hmac"));
        given(redis.reserve("synthetic-token", 7L, PetVerificationFlowType.PET_CREATE, null))
                .willReturn(reservation);
        given(tags.generate("테스트견")).willReturn("테스트견#A1B2");
        given(transactions.createAttempt(7L, command, "테스트견#A1B2", evidence)).willThrow(verificationUnique);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(7L, command, "synthetic-token"))
                .isSameAs(verificationUnique);

        then(tags).should(times(1)).generate("테스트견");
        then(redis).should().release("synthetic-token", "server-reservation");
        then(redis).should(never()).finalize("synthetic-token", "server-reservation");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(redis, transactions);
        order.verify(redis).reserve("synthetic-token", 7L, PetVerificationFlowType.PET_CREATE, null);
        order.verify(transactions).createAttempt(7L, command, "테스트견#A1B2", evidence);
        order.verify(redis).release("synthetic-token", "server-reservation");
    }

    @Test
    void finalizeFailureAfterSuccessfulCreateDoesNotReverseTheSuccess() {
        PetVerificationEvidence evidence = evidence();
        var reservation = new PetVerificationRedisStore.Reservation("server-reservation", evidence);
        PetCreateCommand command = new PetCreateCommand("테스트견", null, null, null, null, null,
                null, null, null, null);
        given(redis.reserve("synthetic-token", 7L, PetVerificationFlowType.PET_CREATE, null))
                .willReturn(reservation);
        given(tags.generate("테스트견")).willReturn("테스트견#A1B2");
        given(transactions.createAttempt(7L, command, "테스트견#A1B2", evidence))
                .willReturn(new PetCreationOutcome(31L, false));
        given(redis.finalize("synthetic-token", "server-reservation"))
                .willThrow(new RuntimeException("synthetic finalize failure"));

        var result = service.create(7L, command, "synthetic-token");

        org.assertj.core.api.Assertions.assertThat(result.petId()).isEqualTo(31L);
        then(redis).should().finalize("synthetic-token", "server-reservation");
    }

    @Test
    void finalizeNoOpAfterCommittedFirstPetStillReturnsTheCommittedActiveAssignmentResponse() {
        PetVerificationEvidence evidence = evidence();
        var reservation = new PetVerificationRedisStore.Reservation("server-reservation", evidence);
        PetCreateCommand command = new PetCreateCommand("테스트견", null, null, null, null, null,
                null, null, null, null);
        given(redis.reserve("synthetic-token", 7L, PetVerificationFlowType.PET_CREATE, null))
                .willReturn(reservation);
        given(tags.generate("테스트견")).willReturn("테스트견#A1B2");
        given(transactions.createAttempt(7L, command, "테스트견#A1B2", evidence))
                .willReturn(new PetCreationOutcome(31L, true));
        given(redis.finalize("synthetic-token", "server-reservation")).willReturn(false);
        given(activeAssignments.assignIfAbsent(7L, 31L)).willReturn(ActivePetAssignmentStatus.ASSIGNED);

        var result = service.create(7L, command, "synthetic-token");

        org.assertj.core.api.Assertions.assertThat(result.petId()).isEqualTo(31L);
        org.assertj.core.api.Assertions.assertThat(result.activePetAssignmentStatus())
                .isEqualTo(ActivePetAssignmentStatus.ASSIGNED);
        then(redis).should().finalize("synthetic-token", "server-reservation");
        then(activeAssignments).should().assignIfAbsent(7L, 31L);
    }

    private PetVerificationEvidence evidence() {
        return new PetVerificationEvidence(PetVerificationProvider.ANIMAL_INFO_V3,
                "a".repeat(64), PetVerificationDeviceType.IMPLANTED, "테스트견", LocalDate.of(2022, 1, 1),
                PetSex.FEMALE, "테스트품종", true);
    }

    private PetCreateCommand command() {
        return new PetCreateCommand("테스트견", null, null, null, null, null,
                null, null, null, null);
    }

    private DataIntegrityViolationException publicTagConflict() {
        return new DataIntegrityViolationException("duplicate public tag",
                new ConstraintViolationException("duplicate", new SQLException(), "insert", "uk_pets_public_tag"));
    }
}
