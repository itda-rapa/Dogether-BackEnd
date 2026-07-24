package itda.media.dto;

import itda.media.domain.MediaPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record MediaUploadRequest(
        @NotNull MediaPurpose purpose,
        @NotBlank String contentType,
        @Positive long sizeBytes
) {
}
