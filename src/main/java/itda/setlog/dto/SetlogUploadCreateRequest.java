package itda.setlog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SetlogUploadCreateRequest(
        @Schema(description = "현재 사용자의 Active Pet ID", example = "12")
        @NotNull Long petId,
        @Schema(
                description = "원본 파일명(Object Key에는 포함되지 않음)",
                example = "walk.mp4",
                maxLength = 255
        )
        @NotBlank @Size(max = 255) String fileName,
        @Schema(
                description = "영상 Content-Type",
                example = "video/mp4",
                allowableValues = {"video/mp4", "video/webm"}
        )
        @NotBlank String contentType,
        @Schema(
                description = "업로드할 파일 크기(bytes)",
                example = "12582912",
                minimum = "1",
                maximum = "209715200"
        )
        @NotNull Long size
) {
}
