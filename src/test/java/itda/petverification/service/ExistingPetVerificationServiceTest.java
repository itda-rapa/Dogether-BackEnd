package itda.petverification.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.PetSex;
import itda.petverification.PetVerificationEvidence;
import itda.petverification.PetVerificationFlowType;
import itda.petverification.PetVerificationRedisStore;
import itda.petverification.domain.PetVerificationDeviceType;
import itda.petverification.domain.PetVerificationProvider;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class ExistingPetVerificationServiceTest {

    @Mock private PetVerificationRedisStore redis;
    @Mock private PetVerificationApplyTransactionService transaction;
    private ExistingPetVerificationService service;

    @BeforeEach
    void setUp() { service = new ExistingPetVerificationService(redis, transaction); }

    @Test
    void nullEmptyAsciiAndUnicodeWhitespaceTokensAreRejectedBeforeRedisReserve() {
        for (String blankToken : java.util.Arrays.asList(null, "", " \t\n", "\u3000")) {
            assertThatThrownBy(() -> service.apply(7L, 31L, blankToken))
                    .isInstanceOf(BusinessException.class)
                    .extracting(error -> ((BusinessException) error).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        then(redis).shouldHaveNoInteractions();
        then(transaction).shouldHaveNoInteractions();
    }

    @Test
    void boundaryWhitespaceTokenIsPassedToRedisWithoutTrimming() {
        String rawToken = " existing-token ";
        var reservation = reservation();
        given(redis.reserve(rawToken, 7L, PetVerificationFlowType.EXISTING_PET_VERIFY, 31L))
                .willReturn(reservation);

        service.apply(7L, 31L, rawToken);

        then(redis).should().reserve(rawToken, 7L, PetVerificationFlowType.EXISTING_PET_VERIFY, 31L);
        then(redis).should().finalize(rawToken, "server-reservation");
        then(transaction).should().apply(7L, 31L, reservation.evidence());
    }

    @Test
    void dbFailureReleasesOnlyItsReservationAndPropagatesTheDatabaseFailure() {
        var reservation = reservation();
        RuntimeException databaseFailure = new RuntimeException("synthetic database failure");
        given(redis.reserve("synthetic-token", 7L, PetVerificationFlowType.EXISTING_PET_VERIFY, 31L))
                .willReturn(reservation);
        org.mockito.Mockito.doThrow(databaseFailure).doNothing()
                .when(transaction).apply(7L, 31L, reservation.evidence());

        assertThatThrownBy(() -> service.apply(7L, 31L, "synthetic-token")).isSameAs(databaseFailure);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(redis, transaction);
        order.verify(redis).reserve("synthetic-token", 7L, PetVerificationFlowType.EXISTING_PET_VERIFY, 31L);
        order.verify(transaction).apply(7L, 31L, reservation.evidence());
        order.verify(redis).release("synthetic-token", "server-reservation");
        then(redis).should(never()).finalize("synthetic-token", "server-reservation");
    }

    @Test
    void releaseFailureDoesNotMaskTheDatabaseFailureAndFinalizeFailureDoesNotReverseSuccess() {
        var reservation = reservation();
        RuntimeException databaseFailure = new RuntimeException("synthetic database failure");
        given(redis.reserve("token-a", 7L, PetVerificationFlowType.EXISTING_PET_VERIFY, 31L)).willReturn(reservation);
        org.mockito.Mockito.doThrow(databaseFailure).doNothing()
                .when(transaction).apply(7L, 31L, reservation.evidence());
        org.mockito.Mockito.doThrow(new RuntimeException("release unavailable"))
                .when(redis).release("token-a", "server-reservation");

        assertThatThrownBy(() -> service.apply(7L, 31L, "token-a")).isSameAs(databaseFailure);

        given(redis.reserve("token-b", 7L, PetVerificationFlowType.EXISTING_PET_VERIFY, 31L)).willReturn(reservation);
        given(redis.finalize("token-b", "server-reservation")).willThrow(new RuntimeException("finalize unavailable"));

        service.apply(7L, 31L, "token-b");

        then(transaction).should(times(2)).apply(7L, 31L, reservation.evidence());
        then(redis).should().finalize("token-b", "server-reservation");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(redis, transaction);
        order.verify(redis).reserve("token-a", 7L, PetVerificationFlowType.EXISTING_PET_VERIFY, 31L);
        order.verify(transaction).apply(7L, 31L, reservation.evidence());
        order.verify(redis).release("token-a", "server-reservation");
        order.verify(redis).reserve("token-b", 7L, PetVerificationFlowType.EXISTING_PET_VERIFY, 31L);
        order.verify(transaction).apply(7L, 31L, reservation.evidence());
        order.verify(redis).finalize("token-b", "server-reservation");
    }

    @Test
    void delegatedApplyBoundaryIsTransactional() throws NoSuchMethodException {
        Transactional transactionBoundary = PetVerificationApplyTransactionService.class
                .getMethod("apply", Long.class, Long.class, PetVerificationEvidence.class)
                .getAnnotation(Transactional.class);

        org.assertj.core.api.Assertions.assertThat(transactionBoundary).isNotNull();
    }

    private PetVerificationRedisStore.Reservation reservation() {
        return new PetVerificationRedisStore.Reservation("server-reservation",
                new PetVerificationEvidence(PetVerificationProvider.ANIMAL_INFO_V3, "a".repeat(64),
                        PetVerificationDeviceType.IMPLANTED, "테스트견", LocalDate.of(2022, 1, 1),
                        PetSex.FEMALE, "테스트품종", true));
    }
}
