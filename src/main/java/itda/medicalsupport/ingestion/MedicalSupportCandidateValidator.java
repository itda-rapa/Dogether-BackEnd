package itda.medicalsupport.ingestion;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Common ingestion boundary validation; adapters remain responsible for source-specific parsing. */
public final class MedicalSupportCandidateValidator {
    private MedicalSupportCandidateValidator() { }

    public static void validate(MedicalSupportCandidate candidate) {
        // List.of는 null 요소를 거부하므로 필수값 검증 전에 NPE가 나지 않도록 null을 허용하는 목록으로 수집한다.
        List<Object> required = Arrays.asList(candidate.sourceUrl(), candidate.sourceOrganization(), candidate.sourceHash(),
                candidate.contentType(), candidate.parserVersion(), candidate.regionScope(), candidate.regionCode(),
                candidate.programYear(), candidate.programName(), candidate.semanticFingerprint(), candidate.hospitalPolicy(),
                candidate.programStatus());
        if (required.stream().anyMatch(value -> value == null || (value instanceof String text && text.isBlank()))) {
            throw new IllegalArgumentException("medical support candidate has missing required fields");
        }
        if (!Objects.equals(candidate.normalizedProgramName(), MedicalSupportCandidate.normalize(candidate.programName()))) {
            throw new IllegalArgumentException("medical support candidate program name is not normalized");
        }
    }
}
