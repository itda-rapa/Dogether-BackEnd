package itda.comment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import itda.comment.dto.CommentCreateRequest;
import itda.comment.dto.CommentListResponse;
import itda.comment.dto.CommentResponse;
import itda.comment.dto.CommentUpdateRequest;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

@Tag(name = "Board Post Comment", description = "게시글 댓글 API")
@SecurityRequirement(name = "bearerAuth")
public interface BoardPostCommentSwaggerSupporter {

    @Operation(summary = "게시글 댓글 작성", description = "현재 활성 반려견으로 공개 범위 내 게시글에 댓글을 작성합니다. 허용 필드는 content뿐입니다.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CommentCreateRequest.class),
                    examples = @ExampleObject("{\"content\":\"같이 산책해요!\"}")
            )
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "댓글 작성 성공",
            useReturnTypeSchema = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {"success":true,"message":"댓글이 등록되었습니다.","data":{"commentId":1,"postId":10,"authorPet":{"petId":2,"publicTag":"dog-2","nickname":"두부","profileUrl":null,"verified":false},"content":"같이 산책해요!","version":0,"createdAt":"2026-08-10T00:00:00Z","updatedAt":"2026-08-10T00:00:00Z"},"error":null}
                            """)
            )
    )
    ResponseEntity<ApiResponse<CommentResponse>> create(
            @Parameter(hidden = true) CurrentUser user,
            @Parameter(description = "게시글 ID") Long postId,
            JsonNode body
    );

    @Operation(summary = "게시글 댓글 목록", description = "공개 범위와 양방향 차단 관계를 적용해 오래된 댓글부터 cursor 방식으로 조회합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "댓글 목록 조회 성공",
            useReturnTypeSchema = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {"success":true,"message":"댓글 목록이 조회되었습니다.","data":{"items":[{"commentId":1,"postId":10,"authorPet":{"petId":2,"publicTag":"dog-2","nickname":"두부","profileUrl":null,"verified":false},"content":"같이 산책해요!","version":0,"createdAt":"2026-08-10T00:00:00Z","updatedAt":"2026-08-10T00:00:00Z"}],"page":{"nextCursor":"MjAyNi0wOC0xMFQwMDowMDowMFp8MQ","hasNext":true}},"error":null}
                            """)
            )
    )
    ResponseEntity<ApiResponse<CommentListResponse>> list(
            @Parameter(hidden = true) CurrentUser user,
            @Parameter(description = "게시글 ID") Long postId,
            @Parameter(description = "다음 페이지 cursor") String cursor,
            @Parameter(description = "1~100, 기본 20") Integer size
    );

    @Operation(summary = "댓글 수정", description = "현재 활성 반려견이 작성한 댓글만 수정할 수 있습니다. content와 version을 모두 포함해야 합니다.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CommentUpdateRequest.class),
                    examples = @ExampleObject("{\"content\":\"수정한 댓글입니다.\",\"version\":0}")
            )
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "댓글 수정 성공",
            useReturnTypeSchema = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {"success":true,"message":"댓글이 수정되었습니다.","data":{"commentId":1,"postId":10,"authorPet":{"petId":2,"publicTag":"dog-2","nickname":"두부","profileUrl":null,"verified":false},"content":"수정한 댓글입니다.","version":1,"createdAt":"2026-08-10T00:00:00Z","updatedAt":"2026-08-10T00:01:00Z"},"error":null}
                            """)
            )
    )
    ResponseEntity<ApiResponse<CommentResponse>> update(
            @Parameter(hidden = true) CurrentUser user,
            @Parameter(description = "댓글 ID") Long commentId,
            JsonNode body
    );

    @Operation(summary = "댓글 삭제", description = "현재 활성 반려견이 작성한 댓글을 soft delete 합니다. 삭제된 게시글의 댓글도 삭제할 수 있습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "댓글 삭제 성공")
    ResponseEntity<Void> delete(
            @Parameter(hidden = true) CurrentUser user,
            @Parameter(description = "댓글 ID") Long commentId
    );
}
