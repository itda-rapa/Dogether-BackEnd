package itda.boardpost.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(
        description = "게시글 수정 요청. strict JSON으로 title, content, mediaIds 중 하나 이상과 "
                + "현재 version이 필요합니다. mediaIds를 생략하면 기존 이미지를 유지하고, null은 허용하지 않습니다.",
        requiredProperties = "version"
)
public record BoardPostPatchRequest(
        @Schema(description = "제목", maxLength = 120) String title,
        @Schema(description = "본문", maxLength = 5000) String content,
        @ArraySchema(
                maxItems = 5,
                uniqueItems = true,
                schema = @Schema(type = "integer", format = "int64", minimum = "1"),
                arraySchema = @Schema(description = "첨부 이미지 Media ID. 생략 시 기존 이미지를 유지하고, 빈 배열은 모두 제거하며, 값은 요청 순서대로 전체 교체됩니다.")
        )
        List<Long> mediaIds,
        @Schema(
                description = "현재 게시글 version. 서버 version과 다르면 409 CONCURRENT_UPDATE_CONFLICT입니다.",
                requiredMode = Schema.RequiredMode.REQUIRED,
                minimum = "0"
        )
        long version
) {
}
