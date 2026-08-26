package itda.medicalsupport.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.medicalsupport.domain.MedicalSupportHospitalPolicy;
import itda.medicalsupport.domain.MedicalSupportProgramStatus;
import itda.medicalsupport.domain.MedicalSupportRegionScope;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MedicalSupportCandidateValidatorTest {

    @Test
    void acceptsValidCandidate() {
        assertThatCode(() -> MedicalSupportCandidateValidator.validate(candidate()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNullRequiredFieldAsValidationFailureNotNpe() {
        MedicalSupportCandidate valid = candidate();
        MedicalSupportCandidate invalid = new MedicalSupportCandidate(null, valid.sourceDocumentUrl(), valid.sourceOrganization(),
                valid.sourcePublishedAt(), valid.fetchedAt(), valid.sourceHash(), valid.contentType(), valid.parserVersion(),
                valid.stableSourceProgramId(), valid.regionCode(), valid.regionScope(), valid.regionSidoName(), valid.regionSigunguName(),
                valid.programYear(), valid.programName(), valid.normalizedProgramName(), valid.summary(), valid.supportAmount(),
                valid.applicationPeriod(), valid.supportTarget(), valid.supportItems(), valid.applicationMethod(),
                valid.animalRegistrationCondition(), valid.incomeWelfareCondition(), valid.contact(),
                valid.hospitalPolicy(), valid.programStatus(), valid.hospitals(), valid.semanticFingerprint());

        assertThatThrownBy(() -> MedicalSupportCandidateValidator.validate(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("medical support candidate has missing required fields");
    }

    @Test
    void rejectsBlankRequiredFieldAsValidationFailure() {
        MedicalSupportCandidate valid = candidate();
        MedicalSupportCandidate invalid = new MedicalSupportCandidate(valid.sourceUrl(), valid.sourceDocumentUrl(), valid.sourceOrganization(),
                valid.sourcePublishedAt(), valid.fetchedAt(), valid.sourceHash(), valid.contentType(), valid.parserVersion(),
                valid.stableSourceProgramId(), valid.regionCode(), valid.regionScope(), valid.regionSidoName(), valid.regionSigunguName(),
                valid.programYear(), "   ", valid.normalizedProgramName(), valid.summary(), valid.supportAmount(),
                valid.applicationPeriod(), valid.supportTarget(), valid.supportItems(), valid.applicationMethod(),
                valid.animalRegistrationCondition(), valid.incomeWelfareCondition(), valid.contact(),
                valid.hospitalPolicy(), valid.programStatus(), valid.hospitals(), valid.semanticFingerprint());

        assertThatThrownBy(() -> MedicalSupportCandidateValidator.validate(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("medical support candidate has missing required fields");
    }

    @Test
    void rejectsNullNormalizedProgramNameWithoutNpe() {
        MedicalSupportCandidate valid = candidate();
        MedicalSupportCandidate invalid = new MedicalSupportCandidate(valid.sourceUrl(), valid.sourceDocumentUrl(), valid.sourceOrganization(),
                valid.sourcePublishedAt(), valid.fetchedAt(), valid.sourceHash(), valid.contentType(), valid.parserVersion(),
                valid.stableSourceProgramId(), valid.regionCode(), valid.regionScope(), valid.regionSidoName(), valid.regionSigunguName(),
                valid.programYear(), null, valid.normalizedProgramName(), valid.summary(), valid.supportAmount(),
                valid.applicationPeriod(), valid.supportTarget(), valid.supportItems(), valid.applicationMethod(),
                valid.animalRegistrationCondition(), valid.incomeWelfareCondition(), valid.contact(),
                valid.hospitalPolicy(), valid.programStatus(), valid.hospitals(), valid.semanticFingerprint());

        assertThat(invalid.normalizedProgramName()).isNull();
        assertThatThrownBy(() -> MedicalSupportCandidateValidator.validate(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("medical support candidate has missing required fields");
    }

    private MedicalSupportCandidate candidate() {
        return new MedicalSupportCandidate(
                "https://official.example/seoul", null, "서울특별시", Instant.EPOCH, Instant.EPOCH,
                "hash", "text/html", "v1", null, "11", MedicalSupportRegionScope.SIDO,
                "서울특별시", null, 2026, "반려동물 진료비 지원", null, null, "20만원 이내", null,
                "대상", "항목", "방법", "동물 등록된 반려동물", "기초생활수급자", "02-120",
                MedicalSupportHospitalPolicy.NOT_PUBLISHED, MedicalSupportProgramStatus.UNKNOWN, List.of(), "fingerprint");
    }
}
