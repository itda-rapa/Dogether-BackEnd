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
import itda.setlog.dto.SetlogListResponse;
import itda.setlog.dto.SetlogReactionResponse;
import itda.setlog.dto.SetlogUploadCreateRequest;
import itda.setlog.dto.SetlogUploadCreateResponse;
import itda.setlog.dto.SetlogUploadCompleteRequest;
import itda.setlog.dto.SetlogResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "Setlog", description = "셋로그 관련 API")
@SecurityRequirement(name = "bearerAuth")
public interface SetlogSwaggerSupporter {

    @Operation(
            summary = "셋로그 업로드 세션 생성",
            description = "Active Pet의 MP4 또는 WebM 영상을 직접 업로드할 수 있는 15분 유효 Presigned PUT 세션을 생성합니다."
    )
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = SetlogUploadCreateRequest.class),
            examples = @ExampleObject("""
                    {
                        "petId":12,
                        "fileName":"walk.mp4",
                        "contentType":"video/mp4",
                        "size":12582912
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "업로드 세션 생성 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"셋로그 업로드 세션 생성 성공",
                                "data":{
                                    "uploadId":"10f7ed34-8aa7-4ffc-b3be-7a72c5d3bf35",
                                    "uploadUrl":"https://example.com/presigned-put",
                                    "objectKey":"setlogs/1/12/10f7ed34-8aa7-4ffc-b3be-7a72c5d3bf35.mp4",
                                    "headers":{"Content-Type":"video/mp4","Content-Length":"12582912"},
                                    "expiresAt":"2026-08-12T09:15:00Z"
                                },
                                "error":null
                            }
                            """)
            )
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "파일명 또는 파일 크기 검증 실패"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "비활성 계정 또는 Active Pet 불일치"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "413",
            description = "파일 크기 200 MiB 초과"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "415",
            description = "지원하지 않는 Content-Type"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "Object Storage 일시 장애 또는 Bucket Versioning 정보 누락"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "502",
            description = "Object Storage가 URL 발급 요청을 거절함"
    )
    ResponseEntity<ApiResponse<SetlogUploadCreateResponse>> createUploadSession(
            @Parameter(hidden = true) CurrentUser currentUser,
            SetlogUploadCreateRequest request
    );

    @Operation(
            summary = "셋로그 업로드 완료",
            description = "업로드 객체의 크기와 형식을 검증한 뒤 사용자 셋로그를 멱등하게 생성합니다. 같은 clientRequestId 재요청은 200을 반환합니다."
    )
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = SetlogUploadCompleteRequest.class),
            examples = @ExampleObject("""
                    {
                                 "clientRequestId":"550e8400-e29b-41d4-a716-446655440000",
                                 "caption":"오늘 산책 완료"
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "업로드 검증 및 셋로그 생성 성공"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "동일 clientRequestId 재요청 성공"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "업로드 객체 Metadata 불일치"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "비활성 계정 또는 Active Pet 불일치"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "업로드 세션 또는 스토리지 객체 없음"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "업로드 만료, 상태 또는 멱등키 충돌"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "502",
            description = "Object Storage가 확인 요청을 거절함"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "Object Storage 일시 장애"
    )
    ResponseEntity<ApiResponse<SetlogResponse>> completeUpload(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "업로드 세션 ID") java.util.UUID uploadId,
            SetlogUploadCompleteRequest request
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
                                        "authorPet":{
                                            "petId":12,
                                            "publicTag":"몽이#4A2F",
                                            "nickname":"몽이",
                                            "profileUrl":null,
                                            "verified":true,
                                            "relationship":"NONE"
                                        },
                                        "mediaUrl":"https://example.com/setlogs/1.mp4",
                                        "mediaUrlExpiresAt":"2026-08-05T00:10:00Z",
                                        "caption":"오늘 산책 완료",
                                        "cuteCount":2,
                                        "likeCount":1,
                                        "myReactions":["LIKE"],
                                        "canInteract":true,
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

    @Operation(
            summary = "셋로그 상세 조회",
            description = "공유 카드 상세 route용 단건 조회입니다. 피드와 동일하게 표시 가능 상태와 양방향 차단 관계를 조회 시점에 재검증하고, Media URL은 새로 발급합니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "셋로그 상세 조회 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"셋로그 상세 조회 성공",
                                "data":{
                                    "setlogId":1,
                                    "source":"USER",
                                    "authorPet":{
                                        "petId":12,
                                        "publicTag":"몽이#4A2F",
                                        "nickname":"몽이",
                                        "profileUrl":null,
                                        "verified":true,
                                        "relationship":"FRIEND"
                                    },
                                    "mediaUrl":"https://example.com/setlogs/1.mp4",
                                    "mediaUrlExpiresAt":"2026-08-05T00:10:00Z",
                                    "caption":"오늘 산책 완료",
                                    "cuteCount":2,
                                    "likeCount":1,
                                    "myReactions":["LIKE"],
                                    "canInteract":true,
                                    "createdAt":"2026-08-05T00:00:00Z"
                                },
                                "error":null
                            }
                            """)
            )
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "셋로그 없음, 표시 불가 상태 또는 양방향 차단으로 접근 불가",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":false,
                                "message":"해당 요청이 실패되었습니다.",
                                "data":null,
                                "error":{
                                    "code":"SETLOG_NOT_FOUND",
                                    "message":"셋로그를 찾을 수 없습니다."
                                }
                            }
                            """)
            )
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "비활성 계정 또는 Active Pet 미선택"
    )
    ResponseEntity<ApiResponse<SetlogResponse>> getSetlog(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "셋로그 ID") Long setlogId
    );

    @Operation(summary = "셋로그 삭제", description = "작성자가 자신의 사용자 셋로그를 논리 삭제하고 스토리지 객체 삭제를 예약합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "삭제 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "소유한 시드 셋로그")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "셋로그 없음 또는 다른 사용자의 셋로그")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 삭제됨")
    ResponseEntity<Void> deleteSetlog(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "셋로그 ID") Long setlogId
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
                                    "cuteCount":2,
                                    "likeCount":2
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
                                    "cuteCount":2,
                                    "likeCount":1
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
