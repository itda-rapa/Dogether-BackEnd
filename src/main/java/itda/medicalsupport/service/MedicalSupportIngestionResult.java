package itda.medicalsupport.service;

import itda.medicalsupport.domain.MedicalSupportIngestionOutcome;
import itda.medicalsupport.domain.MedicalSupportRevision;

public record MedicalSupportIngestionResult(
        String sourceKey,
        MedicalSupportIngestionOutcome outcome,
        MedicalSupportRevision revision,
        boolean created,
        String failureReason
) {
    static MedicalSupportIngestionResult succeeded(String sourceKey, MedicalSupportRevision revision, boolean created) {
        return new MedicalSupportIngestionResult(
                sourceKey, MedicalSupportIngestionOutcome.SUCCEEDED, revision, created, null);
    }

    static MedicalSupportIngestionResult failed(String sourceKey, String failureReason) {
        return new MedicalSupportIngestionResult(
                sourceKey, MedicalSupportIngestionOutcome.FAILED, null, false, failureReason);
    }
}
