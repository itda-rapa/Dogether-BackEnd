package itda.petverification.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.PetSex;
import itda.petverification.PetVerificationHasher;
import itda.petverification.PetVerificationProperties;
import itda.petverification.domain.PetVerificationDeviceType;
import java.time.LocalDate;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AnimalInfoV3AdapterTest {

    private AnimalInfoV3Adapter adapter;

    @BeforeEach
    void setUp() {
        PetVerificationProperties properties = new PetVerificationProperties(
                "synthetic-pet-verification-hmac-secret", "synthetic-service-key",
                Duration.ofMinutes(15), Duration.ofSeconds(1), Duration.ofSeconds(1));
        adapter = new AnimalInfoV3Adapter(properties, new PetVerificationHasher(properties));
    }

    @Test
    void mapsTheWrappedSuccessEnvelopeAndOnlyStripsCanonicalBoundaryWhitespace() {
        var evidence = normalize(successItem("  REG- SYN- 001  ", "Y", "2022-01-01", "암컷", "중성"));

        assertThat(evidence.deviceType()).isEqualTo(PetVerificationDeviceType.IMPLANTED);
        assertThat(evidence.registeredName()).isEqualTo("테스트견");
        assertThat(evidence.birthDate()).isEqualTo(LocalDate.of(2022, 1, 1));
        assertThat(evidence.sex()).isEqualTo(PetSex.FEMALE);
        assertThat(evidence.neutered()).isTrue();
        assertThat(evidence.registrationNumberHmac())
                .isEqualTo(new PetVerificationHasher(new PetVerificationProperties(
                        "synthetic-pet-verification-hmac-secret", "", null, null, null))
                        .registrationNumber("REG- SYN- 001"));
    }

    @Test
    void rejectsBlankCanonicalIdentityAndUnsupportedNonblankProviderValuesAsUnavailable() {
        assertUnavailable(() -> normalize(successItem(" \u3000 ", "Y", "2022-01-01", "암컷", "중성")));
        assertUnavailable(() -> normalize(successItem("REG-SYN-003", "UNSUPPORTED", "2022-01-01", "암컷", "중성")));
        assertUnavailable(() -> normalize(successItem("REG-SYN-004", "Y", "2022/01/01", "암컷", "중성")));
        assertUnavailable(() -> normalize(successItem("REG-SYN-005", "Y", "2022-01-01", "수컷", "중성")));
    }

    @Test
    void classifiesOnlyExplicitResultMessageIdentifierFormatErrorsAsValidation() {
        assertErrorCode(() -> normalize(response("10", "INVALID dog_reg_no format", null, null)),
                ErrorCode.VALIDATION_FAILED);
        assertErrorCode(() -> normalize(response("10", "INVALID REQUEST PARAMETER ERROR.", null, null)),
                ErrorCode.PET_VERIFICATION_UNAVAILABLE);
        assertErrorCode(() -> normalize(response("10", "INVALID REQUEST PARAMETER ERROR.",
                        "rfid_cd format is invalid", null)),
                ErrorCode.PET_VERIFICATION_UNAVAILABLE);
        assertErrorCode(() -> normalize(response("03", "NO DATA", null, null)),
                ErrorCode.PET_VERIFICATION_UNAVAILABLE);
        assertErrorCode(() -> normalize(response("99", "NO DATA", null, null)),
                ErrorCode.PET_VERIFICATION_UNAVAILABLE);
        assertErrorCode(() -> normalize(response("10", "NO MATCH", null, null)),
                ErrorCode.PET_VERIFICATION_UNAVAILABLE);
    }

    private itda.petverification.domain.PetVerification.Evidence normalize(AnimalInfoV3RawResponse response) {
        return ReflectionTestUtils.invokeMethod(adapter, "normalize", response);
    }

    private AnimalInfoV3RawResponse successItem(String dogRegNo, String deviceType, String birthDate,
                                                  String sex, String neutered) {
        return response("00", "any successful message", null,
                new AnimalInfoV3RawResponse.Item(dogRegNo, deviceType, "테스트견", birthDate,
                        sex, "테스트품종", neutered, "승인"));
    }

    private AnimalInfoV3RawResponse response(String code, String message, String error,
                                              AnimalInfoV3RawResponse.Item item) {
        return new AnimalInfoV3RawResponse(new AnimalInfoV3RawResponse.Response(
                new AnimalInfoV3RawResponse.Header(code, message, error),
                item == null ? null : new AnimalInfoV3RawResponse.Body(item)));
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertErrorCode(operation, ErrorCode.PET_VERIFICATION_UNAVAILABLE);
    }

    private void assertErrorCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
                                 ErrorCode errorCode) {
        assertThatThrownBy(operation).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode()).isEqualTo(errorCode);
    }
}
