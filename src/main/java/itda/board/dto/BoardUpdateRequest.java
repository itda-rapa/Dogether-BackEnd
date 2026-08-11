package itda.board.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record BoardUpdateRequest(
        @Schema(
                description = "게시판 이름",
                example = "자유게시판",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String name,

        @Schema(
                description = "현재 게시판 버전",
                example = "0",
                minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        long version
) {
}
