package itda.medicalsupport.dto;

import itda.medicalsupport.domain.MedicalSupportReviewStatus;
import itda.medicalsupport.service.MedicalSupportIngestionResult;

public record MedicalSupportIngestionResponse(long revisionId, boolean created, MedicalSupportReviewStatus reviewStatus) {
    public static MedicalSupportIngestionResponse from(MedicalSupportIngestionResult result) {
        return new MedicalSupportIngestionResponse(result.revision().getId(), result.created(), result.revision().getReviewStatus());
    }
}
