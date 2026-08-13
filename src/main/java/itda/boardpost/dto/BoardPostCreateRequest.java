package itda.boardpost.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "게시글 작성 요청")
public record BoardPostCreateRequest(
        @Schema(description = "제목", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 120) String title,
        @Schema(description = "본문", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 5000) String content,
        @Schema(description = "첨부 이미지 Media ID (최대 5개)") List<Long> mediaIds
) {
    public BoardPostCreateRequest(String title, String content) {
        this(title, content, List.of());
    }
}
