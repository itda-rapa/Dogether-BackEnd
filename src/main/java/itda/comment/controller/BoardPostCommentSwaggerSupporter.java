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
import itda.comment.dto.CommentReactionResponse;
import itda.comment.dto.CommentUpdateRequest;
import itda.comment.domain.CommentReactionType;
import itda.chat.dto.EnsureDirectRoomResult;
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
                            {"success":true,"message":"댓글이 등록되었습니다.","data":{"commentId":1,"postId":10,"parentCommentId":null,"depth":0,"authorPet":{"petId":2,"publicTag":"dog-2","nickname":"두부","profileUrl":null,"verified":false},"content":"같이 산책해요!","version":0,"createdAt":"2026-08-10T00:00:00Z","updatedAt":"2026-08-10T00:00:00Z"},"error":null}
                            """)
            )
    )
    ResponseEntity<ApiResponse<CommentResponse>> create(
            @Parameter(hidden = true) CurrentUser user,
            @Parameter(description = "게시글 ID") Long postId,
            JsonNode body
    );

    @Operation(
            summary = "댓글 작성자와 DIRECT 채팅방 연결",
            description = "게시글 작성 Pet과 직접 작성된 root 댓글 작성 Pet 사이의 기존 DIRECT 방을 조회하거나 생성합니다. 현재 Active Pet이 두 대상 Pet 중 하나인 경우에만 호출할 수 있으며 요청 본문은 없습니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "DIRECT 채팅방 조회 또는 생성 성공",
            useReturnTypeSchema = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {"success":true,"message":"DIRECT 채팅방이 연결되었습니다.","data":{"roomId":1,"isNew":true},"error":null}
                            """)
            )
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "동일 Pet 또는 동일 User 소유 Pet 간 DIRECT 연결 불가"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "현재 Active Pet이 게시글 작성 Pet 또는 Root 댓글 작성 Pet이 아닌 경우"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "게시글·댓글이 없거나 삭제·차단된 경우 (기존 Board/Chat existence hiding 정책)"
    )
    ResponseEntity<ApiResponse<EnsureDirectRoomResult>> ensureDirectRoom(
            @Parameter(hidden = true) CurrentUser user,
            @Parameter(description = "게시글 ID") Long postId,
            @Parameter(description = "직접 작성된 root 댓글 ID") Long commentId
    );

    @Operation(summary = "게시글 대댓글 작성", description = "상위 댓글 아래에 최대 3-depth까지 대댓글을 작성합니다. 허용 필드는 content뿐입니다.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CommentCreateRequest.class),
                    examples = @ExampleObject("{\"content\":\"저도 같은 경험이 있었어요.\"}")
            )
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "대댓글 작성 성공",
            useReturnTypeSchema = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {"success":true,"message":"대댓글이 등록되었습니다.","data":{"commentId":2,"postId":10,"parentCommentId":1,"depth":1,"authorPet":{"petId":3,"publicTag":"dog-3","nickname":"보리","profileUrl":null,"verified":false},"content":"저도 같은 경험이 있었어요.","version":0,"createdAt":"2026-08-10T00:01:00Z","updatedAt":"2026-08-10T00:01:00Z"},"error":null}
                            """)
            )
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "상위 댓글이 depth 3인 경우 (COMMENT_DEPTH_EXCEEDED)"
    )
    ResponseEntity<ApiResponse<CommentResponse>> createReply(
            @Parameter(hidden = true) CurrentUser user,
            @Parameter(description = "상위 댓글 ID") Long parentCommentId,
            JsonNode body
    );

    @Operation(summary = "게시글 댓글 목록", description = "공개 범위와 양방향 차단 관계를 적용해 Root thread를 오래된 순으로 cursor 조회합니다. size는 Root thread 수이며 각 thread는 중첩 replies로 반환됩니다. 활성 노드의 helpfulCount/helpfulByMe는 HELPFUL 상태이고, tombstone 노드에서는 null입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "댓글 목록 조회 성공",
            useReturnTypeSchema = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {"success":true,"message":"댓글 목록이 조회되었습니다.","data":{"items":[{"commentId":1,"postId":10,"parentCommentId":null,"depth":0,"deleted":false,"authorPet":{"petId":2,"publicTag":"dog-2","nickname":"두부","profileUrl":null,"verified":false},"content":"같이 산책해요!","version":0,"createdAt":"2026-08-10T00:00:00Z","updatedAt":"2026-08-10T00:00:00Z","helpfulCount":2,"helpfulByMe":true,"replies":[{"commentId":2,"postId":10,"parentCommentId":1,"depth":1,"deleted":false,"authorPet":{"petId":3,"publicTag":"dog-3","nickname":"보리","profileUrl":null,"verified":false},"content":"동의해요!","version":0,"createdAt":"2026-08-10T00:01:00Z","updatedAt":"2026-08-10T00:01:00Z","helpfulCount":0,"helpfulByMe":false,"replies":[]}]}],"page":{"nextCursor":"MjAyNi0wOC0xMFQwMDowMDowMFp8MQ","hasNext":true}},"error":null}
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
                            {"success":true,"message":"댓글이 수정되었습니다.","data":{"commentId":1,"postId":10,"parentCommentId":null,"depth":0,"authorPet":{"petId":2,"publicTag":"dog-2","nickname":"두부","profileUrl":null,"verified":false},"content":"수정한 댓글입니다.","version":1,"createdAt":"2026-08-10T00:00:00Z","updatedAt":"2026-08-10T00:01:00Z"},"error":null}
                            """)
            )
    )
    ResponseEntity<ApiResponse<CommentResponse>> update(
            @Parameter(hidden = true) CurrentUser user,
            @Parameter(description = "댓글 ID") Long commentId,
            JsonNode body
    );

    @Operation(summary = "댓글 삭제", description = "현재 활성 반려견이 작성한 Root 댓글 또는 대댓글을 soft delete 합니다. 삭제된 게시글의 댓글도 삭제할 수 있습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "댓글 삭제 성공")
    ResponseEntity<Void> delete(
            @Parameter(hidden = true) CurrentUser user,
            @Parameter(description = "댓글 ID") Long commentId
    );

    @Operation(summary = "댓글 HELPFUL 추가", description = "현재 활성 반려견의 HELPFUL 반응을 멱등하게 추가합니다. 요청 본문은 없습니다. Comment Reaction은 HELPFUL만 허용하며 LIKE는 지원하지 않습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "반응 추가 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "지원하지 않는 반응 타입 (VALIDATION_FAILED)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "활성 반려견 필요 또는 자기 댓글 반응 금지")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공개 범위에서 찾을 수 없는 게시글 또는 댓글")
    ResponseEntity<ApiResponse<CommentReactionResponse>> addReaction(
            @Parameter(hidden = true) CurrentUser user,
            @Parameter(description = "댓글 ID") Long commentId,
            @Parameter(description = "반응 타입 (HELPFUL만 허용)", example = "HELPFUL") CommentReactionType type
    );

    @Operation(summary = "댓글 HELPFUL 취소", description = "현재 활성 반려견의 HELPFUL 반응을 멱등하게 취소합니다. 요청 본문은 없습니다. 차단된 target 또는 조상은 404로 은닉됩니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "반응 취소 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "지원하지 않는 반응 타입 (VALIDATION_FAILED)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "활성 반려견 필요 또는 자기 댓글 반응 금지")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공개 범위에서 찾을 수 없는 게시글 또는 댓글")
    ResponseEntity<ApiResponse<CommentReactionResponse>> removeReaction(
            @Parameter(hidden = true) CurrentUser user,
            @Parameter(description = "댓글 ID") Long commentId,
            @Parameter(description = "반응 타입 (HELPFUL만 허용)", example = "HELPFUL") CommentReactionType type
    );
}
