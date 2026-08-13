package itda.petverification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.PetSex;
import itda.pet.service.MyPetQueryService;
import itda.petverification.PetVerificationEvidence;
import itda.petverification.PetVerificationFlowType;
import itda.petverification.PetVerificationRedisStore;
import itda.petverification.domain.PetVerificationDeviceType;
import itda.petverification.domain.PetVerificationProvider;
import itda.petverification.domain.PetVerification.Evidence;
import itda.petverification.dto.PetVerificationIdentifierType;
import itda.petverification.dto.PetVerificationRequest;
import itda.petverification.provider.AnimalInfoV3Adapter;
import itda.petverification.repository.PetVerificationRepository;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PetVerificationIssueServiceTest {

    @Mock private MyPetQueryService myPetQueryService;
    @Mock private PetVerificationRepository verificationRepository;
    @Mock private AnimalInfoV3Adapter provider;
    @Mock private PetVerificationRedisStore redisStore;

    private PetVerificationIssueService service;

    @BeforeEach
    void setUp() {
        service = new PetVerificationIssueService(myPetQueryService, verificationRepository, provider, redisStore);
    }

    @Test
    void rejectsDtoContractViolationsBeforeCallingTheProvider() {
        assertValidation(new PetVerificationRequest(PetVerificationFlowType.PET_CREATE, 1L,
                PetVerificationIdentifierType.REGISTRATION_NUMBER, "REG-SYN", "Synthetic Owner", null));
        assertValidation(new PetVerificationRequest(PetVerificationFlowType.EXISTING_PET_VERIFY, 0L,
                PetVerificationIdentifierType.RFID, "RFID-SYN", null, LocalDate.of(1971, 5, 5)));
        assertValidation(new PetVerificationRequest(PetVerificationFlowType.PET_CREATE, null,
                PetVerificationIdentifierType.RFID, "  ", "Synthetic Owner", null));
        assertValidation(new PetVerificationRequest(PetVerificationFlowType.PET_CREATE, null,
                PetVerificationIdentifierType.RFID, "RFID-SYN", "  ", null));
        assertValidation(new PetVerificationRequest(PetVerificationFlowType.PET_CREATE, null,
                PetVerificationIdentifierType.RFID, "RFID-SYN", "Synthetic Owner", LocalDate.of(1971, 5, 5)));

        then(provider).shouldHaveNoInteractions();
        then(redisStore).shouldHaveNoInteractions();
    }

    @Test
    void petCreateIssuesEvidenceAndReturnsAFreeFormPrefill() {
        PetVerificationEvidence redisEvidence = evidence();
        given(provider.verify(any())).willReturn(providerEvidence());
        given(redisStore.issue(eq(7L), eq(PetVerificationFlowType.PET_CREATE), eq(null), eq(redisEvidence)))
                .willReturn(new PetVerificationRedisStore.IssuedToken("synthetic-token",
                        Instant.parse("2026-08-12T12:15:00Z")));

        var response = service.issue(7L, new PetVerificationRequest(PetVerificationFlowType.PET_CREATE, null,
                PetVerificationIdentifierType.REGISTRATION_NUMBER, "REG-SYN-001", " Synthetic Owner ", null));

        assertThat(response.verificationToken()).isEqualTo("synthetic-token");
        assertThat(response.petPrefill()).isNotNull();
        assertThat(response.petPrefill().nickname()).isEqualTo("테스트견");
        assertThat(response.petPrefill().breedName()).isEqualTo("테스트품종");
        assertThat(response.petPrefill().birthDate()).isEqualTo(LocalDate.of(2022, 1, 1));
        assertThat(response.petPrefill().sex()).isEqualTo(PetSex.FEMALE);
        assertThat(response.petPrefill().neutered()).isTrue();
        then(myPetQueryService).shouldHaveNoInteractions();
        then(verificationRepository).should().existsByRegistrationNumberHmac("a".repeat(64));
        then(provider).should().verify(argThat(request -> request.ownerName().equals("Synthetic Owner")
                && request.ownerBirthDate() == null));
    }

    @Test
    void existingPetPreflightRunsBeforeProviderAndReturnsNoPrefill() {
        PetVerificationEvidence redisEvidence = evidence();
        given(verificationRepository.existsByPet_Id(31L)).willReturn(false);
        given(provider.verify(any())).willReturn(providerEvidence());
        given(redisStore.issue(7L, PetVerificationFlowType.EXISTING_PET_VERIFY, 31L, redisEvidence))
                .willReturn(new PetVerificationRedisStore.IssuedToken("synthetic-token",
                        Instant.parse("2026-08-12T12:15:00Z")));

        var response = service.issue(7L, new PetVerificationRequest(PetVerificationFlowType.EXISTING_PET_VERIFY,
                31L, PetVerificationIdentifierType.RFID, "RFID-SYN-001", null, LocalDate.of(1971, 5, 5)));

        assertThat(response.petPrefill()).isNull();
        then(myPetQueryService).should().requireOwnedUndeletedPet(7L, 31L);
        then(verificationRepository).should().existsByPet_Id(31L);
        then(provider).should().verify(argThat(request -> request.ownerName() == null
                && request.formattedOwnerBirthDate().equals("710505")));
    }

    @Test
    void existingPetAlreadyVerifiedIsRejectedWithoutCallingProvider() {
        given(verificationRepository.existsByPet_Id(31L)).willReturn(true);

        assertThatThrownBy(() -> service.issue(7L, new PetVerificationRequest(
                PetVerificationFlowType.EXISTING_PET_VERIFY, 31L, PetVerificationIdentifierType.RFID,
                "RFID-SYN-001", null, LocalDate.of(1971, 5, 5))))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.PET_VERIFICATION_CONFLICT);

        then(provider).shouldHaveNoInteractions();
        then(redisStore).shouldHaveNoInteractions();
    }

    @Test
    void duplicateCanonicalRegistrationIsRejectedBeforeIssuingRedisEvidence() {
        given(provider.verify(any())).willReturn(providerEvidence());
        given(verificationRepository.existsByRegistrationNumberHmac("a".repeat(64))).willReturn(true);

        assertThatThrownBy(() -> service.issue(7L, new PetVerificationRequest(
                PetVerificationFlowType.PET_CREATE, null, PetVerificationIdentifierType.REGISTRATION_NUMBER,
                "REG-SYN-001", "Synthetic Owner", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.PET_VERIFICATION_CONFLICT);

        then(redisStore).shouldHaveNoInteractions();
    }

    private void assertValidation(PetVerificationRequest request) {
        assertThatThrownBy(() -> service.issue(7L, request)).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private PetVerificationEvidence evidence() {
        return new PetVerificationEvidence(PetVerificationProvider.ANIMAL_INFO_V3, "a".repeat(64),
                PetVerificationDeviceType.IMPLANTED, "테스트견", LocalDate.of(2022, 1, 1),
                PetSex.FEMALE, "테스트품종", true);
    }

    private Evidence providerEvidence() {
        return new Evidence(PetVerificationProvider.ANIMAL_INFO_V3, "a".repeat(64),
                PetVerificationDeviceType.IMPLANTED, "테스트견", LocalDate.of(2022, 1, 1),
                PetSex.FEMALE, "테스트품종", true);
    }
}
