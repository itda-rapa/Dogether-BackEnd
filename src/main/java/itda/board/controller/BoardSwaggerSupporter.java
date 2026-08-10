package itda.board.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import itda.board.dto.BoardResponse;
import itda.common.dto.ApiResponse;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "Board", description = "게시판 API")
@SecurityRequirement(name = "bearerAuth")
public interface BoardSwaggerSupporter {

    @Operation(summary = "게시판 목록 조회", description = "게시판 목록을 ID 오름차순으로 조회합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "게시판 목록 조회 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"게시판 목록이 조회되었습니다.",
                                "data":[{"boardId":1,"name":"자유게시판","version":0}],
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<List<BoardResponse>>> getBoards();
}
