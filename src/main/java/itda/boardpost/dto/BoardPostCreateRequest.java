package itda.boardpost.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "게시글 작성 요청")
public record BoardPostCreateRequest(
        @Schema(description = "제목", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 120) String title,
        @Schema(description = "본문", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 5000) String content,
        @Schema(description = "첨부 이미지 Media ID (최대 5개)") List<Long> mediaIds,
        @Schema(description = "문화시설 reference candidate ID. 생략 또는 null이면 장소를 지정하지 않습니다.",
                types = {"integer", "null"}, format = "int32", minimum = "1") Integer placeId
) {
    public BoardPostCreateRequest(String title, String content) {
        this(title, content, List.of(), null);
    }

    public BoardPostCreateRequest(String title, String content, List<Long> mediaIds) {
        this(title, content, mediaIds, null);
    }

    public BoardPostCreateRequest(String title, String content, Integer placeId, List<Long> mediaIds) {
        this(title, content, mediaIds, placeId);
    }
}
