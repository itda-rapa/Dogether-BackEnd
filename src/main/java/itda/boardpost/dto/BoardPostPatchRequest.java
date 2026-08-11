package itda.boardpost.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "게시글 수정 요청. title 또는 content 중 하나 이상과 version이 필요합니다.")
public record BoardPostPatchRequest(
        @Schema(description = "제목", maxLength = 120) String title,
        @Schema(description = "본문", maxLength = 5000) String content,
        @Schema(description = "현재 게시글 version", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") long version
) {
}
