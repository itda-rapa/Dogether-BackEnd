package itda.setlog.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.greeting.dto.GreetingResponse;
import itda.setlog.domain.ReactionType;
import itda.setlog.dto.SetlogCreateRequest;
import itda.setlog.dto.SetlogCreateResponse;
import itda.setlog.dto.SetlogListResponse;
import itda.setlog.dto.SetlogReactionResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "Setlog", description = "셋로그 관련 API")
@SecurityRequirement(name = "bearerAuth")
public interface SetlogSwaggerSupporter {

    @Operation(summary = "셋로그 생성", description = "셋로그를 생성하는 API")
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = SetlogCreateRequest.class),
            examples = @ExampleObject("""
                    {
                        "mediaId":1,
                        "caption":"오늘 산책 완료"
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "셋로그 생성 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"셋로그 생성 성공",
                                "data":{
                                    "setlogId":1,
                                    "mediaId":1,
                                    "caption":"오늘 산책 완료",
                                    "createdAt":"2026-08-05T00:00:00Z"
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<SetlogCreateResponse>> createSetlog(
            @Parameter(hidden = true) CurrentUser currentUser,
            SetlogCreateRequest request
    );

    @Operation(
            summary = "셋로그 목록 조회",
            description = "최신순 커서 페이지네이션으로 홈 셋로그를 조회하는 API"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "셋로그 목록 조회 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"셋로그 조회 성공",
                                "data":{
                                    "items":[{
                                        "setlogId":1,
                                        "source":"SEED",
                                        "caption":"오늘 산책 완료",
                                        "reactionCount":3,
                                        "createdAt":"2026-08-05T00:00:00Z"
                                    }],
                                    "nextCursor":"MjAyNi0wOC0wNVQwMDowMDowMFp8MQ",
                                    "hasNext":true
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<SetlogListResponse>> getSetlogs(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "이전 응답의 다음 페이지 커서")
            String cursor,
            @Parameter(description = "조회 개수(기본 20, 최대 100)")
            Integer size
    );

    @Operation(summary = "셋로그 반응 추가", description = "셋로그에 반응을 추가하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "셋로그 반응 추가 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"셋로그 반응 추가 성공",
                                "data":{
                                    "setlogId":1,
                                    "type":"LIKE",
                                    "reacted":true,
                                    "reactionCount":4
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<SetlogReactionResponse>> addReaction(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "셋로그 ID") Long setlogId,
            @Parameter(description = "반응 타입") ReactionType type
    );

    @Operation(summary = "셋로그 반응 취소", description = "셋로그 반응을 취소하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "셋로그 반응 취소 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"셋로그 반응 취소 성공",
                                "data":{
                                    "setlogId":1,
                                    "type":"LIKE",
                                    "reacted":false,
                                    "reactionCount":3
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<SetlogReactionResponse>> removeReaction(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "셋로그 ID") Long setlogId,
            @Parameter(description = "반응 타입") ReactionType type
    );

    @Operation(summary = "인사 보내기", description = "셋로그 작성자에게 인사를 보내는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "인사 보내기 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"인사 전송 및 채팅방 생성 성공",
                                "data":{
                                    "greetingId":1,
                                    "setlogId":1,
                                    "status":"SENT",
                                    "directRoomId":100
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<GreetingResponse>> sendGreeting(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "셋로그 ID") Long setlogId
    );
}
