package itda.board.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record BoardCreateRequest(
        @Schema(
                description = "게시판 이름",
                example = "자유게시판",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String name
) {
}
