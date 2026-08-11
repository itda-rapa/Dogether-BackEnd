package itda.board.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import itda.board.dto.BoardCreateRequest;
import itda.board.dto.BoardResponse;
import itda.board.dto.BoardUpdateRequest;
import itda.common.dto.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

@Tag(name = "Admin Board", description = "관리자 게시판 API")
@SecurityRequirement(name = "bearerAuth")
public interface AdminBoardSwaggerSupporter {

    @Operation(summary = "게시판 등록", description = "관리자가 게시판을 등록합니다.")
    @RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = BoardCreateRequest.class),
            examples = @ExampleObject("""
                    {"name":"자유게시판"}
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "게시판 등록 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"게시판이 등록되었습니다.",
                                "data":{"boardId":1,"name":"자유게시판","version":0},
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<BoardResponse>> createBoard(
            BoardCreateRequest request
    );

    @Operation(summary = "게시판 수정", description = "name과 현재 version을 모두 포함해 게시판을 수정합니다.")
    @RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = BoardUpdateRequest.class),
            examples = @ExampleObject("""
                    {"name":"자유게시판","version":0}
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "게시판 수정 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"게시판이 수정되었습니다.",
                                "data":{"boardId":1,"name":"자유게시판","version":1},
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<BoardResponse>> updateBoard(
            @Parameter(description = "게시판 ID") Long boardId,
            JsonNode requestBody
    );

    @Operation(summary = "게시판 삭제", description = "PUBLISHED 또는 DELETED 게시글이 하나도 없는 게시판만 물리 삭제합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "게시판 삭제 성공 (응답 body 없음)")
    ResponseEntity<Void> deleteBoard(@Parameter(description = "게시판 ID") Long boardId);
}
