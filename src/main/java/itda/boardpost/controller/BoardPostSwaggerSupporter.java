package itda.boardpost.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import itda.boardpost.dto.BoardPostFeedResponse;
import itda.boardpost.dto.BoardPostCreateRequest;
import itda.boardpost.dto.BoardPostPatchRequest;
import itda.boardpost.dto.BoardPostReactionResponse;
import itda.boardpost.dto.BoardPostResponse;
import itda.boardpost.domain.BoardPostReactionType;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

@Tag(name = "Board Post", description = "지역 게시글 API")
@SecurityRequirement(name = "bearerAuth")
public interface BoardPostSwaggerSupporter {

    @Operation(summary = "게시글 작성", description = "현재 활성 반려견으로 게시글을 작성합니다. title과 content는 필수이며 mediaIds는 선택적으로 최대 5개까지 첨부할 수 있습니다.")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = BoardPostCreateRequest.class),
            examples = @ExampleObject("""
                    {"title":"산책 친구 구해요","content":"오늘 저녁 같이 산책해요.","mediaIds":[10,11]}
                    """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "게시글 작성 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            examples = @ExampleObject("""
                    {"success":true,"message":"게시글이 등록되었습니다.","data":{"postId":1,"boardId":1,"authorPet":{"petId":2,"publicTag":"dog-2","nickname":"두부","profileUrl":null,"verified":false},"title":"산책 친구 구해요","content":"오늘 저녁 같이 산책해요.","images":[{"mediaId":10,"url":"https://...","displayOrder":0}],"reactionCount":0,"reactedByMe":false,"version":0,"createdAt":"2026-08-10T00:00:00Z","updatedAt":"2026-08-10T00:00:00Z"},"error":null}
                    """)))
    ResponseEntity<ApiResponse<BoardPostResponse>> create(@Parameter(hidden = true) CurrentUser user, @Parameter(description = "게시판 ID") Long boardId, JsonNode body);

    @Operation(summary = "지역 게시글 피드", description = "현재 사용자 지역의 공개 게시글을 최신순 cursor 방식으로 조회합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "게시글 피드 조회 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            examples = @ExampleObject("""
                    {"success":true,"message":"게시글 목록이 조회되었습니다.","data":{"items":[{"postId":1,"boardId":1,"authorPet":{"petId":2,"publicTag":"dog-2","nickname":"두부","profileUrl":null,"verified":false},"title":"산책 친구 구해요","content":"오늘 저녁 같이 산책해요.","images":[],"reactionCount":4,"reactedByMe":true,"version":0,"createdAt":"2026-08-10T00:00:00Z","updatedAt":"2026-08-10T00:00:00Z"}],"page":{"nextCursor":"MjAyNi0wOC0xMFQwMDowMDowMFp8MQ","hasNext":true}},"error":null}
                    """)))
    ResponseEntity<ApiResponse<BoardPostFeedResponse>> feed(@Parameter(hidden = true) CurrentUser user, @Parameter(description = "게시판 ID") Long boardId, @Parameter(description = "다음 페이지 cursor") String cursor, @Parameter(description = "1~100, 기본 20") Integer size);

    @Operation(summary = "게시글 상세", description = "지역·차단 공개 범위를 만족하는 게시글을 조회합니다. 작성자는 지역 변경 뒤에도 자신의 게시글을 조회할 수 있습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "게시글 상세 조회 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            examples = @ExampleObject("""
                    {"success":true,"message":"게시글이 조회되었습니다.","data":{"postId":1,"boardId":1,"authorPet":{"petId":2,"publicTag":"dog-2","nickname":"두부","profileUrl":null,"verified":false},"title":"산책 친구 구해요","content":"오늘 저녁 같이 산책해요.","images":[],"reactionCount":4,"reactedByMe":true,"version":0,"createdAt":"2026-08-10T00:00:00Z","updatedAt":"2026-08-10T00:00:00Z"},"error":null}
                    """)))
    ResponseEntity<ApiResponse<BoardPostResponse>> detail(@Parameter(hidden = true) CurrentUser user, @Parameter(description = "게시글 ID") Long postId);

    @Operation(summary = "게시글 수정", description = "version은 필수이며 title 또는 content 중 하나 이상을 포함해야 합니다. 허용 필드는 title, content, version입니다.")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = BoardPostPatchRequest.class),
            examples = @ExampleObject("""
                    {"title":"수정된 제목","version":0}
                    """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "게시글 수정 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            examples = @ExampleObject("""
                    {"success":true,"message":"게시글이 수정되었습니다.","data":{"postId":1,"boardId":1,"authorPet":{"petId":2,"publicTag":"dog-2","nickname":"두부","profileUrl":null,"verified":false},"title":"수정된 제목","content":"오늘 저녁 같이 산책해요.","images":[],"reactionCount":4,"reactedByMe":false,"version":1,"createdAt":"2026-08-10T00:00:00Z","updatedAt":"2026-08-10T00:01:00Z"},"error":null}
                    """)))
    ResponseEntity<ApiResponse<BoardPostResponse>> update(@Parameter(hidden = true) CurrentUser user, @Parameter(description = "게시글 ID") Long postId, JsonNode body);

    @Operation(summary = "게시글 좋아요 추가", description = "현재 활성 반려견의 LIKE 반응을 멱등하게 추가합니다. 요청 본문은 없습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "반응 추가 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            examples = @ExampleObject("""
                    {"success":true,"message":"게시글 반응 상태가 변경되었습니다.","data":{"postId":101,"type":"LIKE","reacted":true,"reactionCount":4},"error":null}
                    """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "지원하지 않는 반응 타입 (VALIDATION_FAILED)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패 (UNAUTHORIZED)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "활성 반려견 필요 또는 자기 게시글 반응 금지 (ACTIVE_PET_REQUIRED, BOARD_POST_SELF_REACTION_FORBIDDEN)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공개 범위에서 찾을 수 없는 게시글 (BOARD_POST_NOT_FOUND)")
    ResponseEntity<ApiResponse<BoardPostReactionResponse>> addReaction(
            @Parameter(hidden = true) CurrentUser user,
            @Parameter(description = "게시글 ID") Long postId,
            @Parameter(description = "반응 타입", example = "LIKE") BoardPostReactionType type
    );

    @Operation(summary = "게시글 좋아요 취소", description = "현재 활성 반려견의 LIKE 반응을 멱등하게 취소합니다. 요청 본문은 없습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "반응 취소 성공", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            examples = @ExampleObject("""
                    {"success":true,"message":"게시글 반응 상태가 변경되었습니다.","data":{"postId":101,"type":"LIKE","reacted":false,"reactionCount":3},"error":null}
                    """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "지원하지 않는 반응 타입 (VALIDATION_FAILED)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패 (UNAUTHORIZED)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "활성 반려견 필요 또는 자기 게시글 반응 금지 (ACTIVE_PET_REQUIRED, BOARD_POST_SELF_REACTION_FORBIDDEN)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공개 범위에서 찾을 수 없는 게시글 (BOARD_POST_NOT_FOUND)")
    ResponseEntity<ApiResponse<BoardPostReactionResponse>> removeReaction(
            @Parameter(hidden = true) CurrentUser user,
            @Parameter(description = "게시글 ID") Long postId,
            @Parameter(description = "반응 타입", example = "LIKE") BoardPostReactionType type
    );

    @Operation(summary = "게시글 삭제", description = "현재 활성 반려견이 작성한 게시글을 soft delete 합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "게시글 삭제 성공")
    ResponseEntity<Void> delete(@Parameter(hidden = true) CurrentUser user, @Parameter(description = "게시글 ID") Long postId);
}
