package itda.friend.controller;

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
import itda.friend.dto.FriendRequestCreateRequest;
import itda.friend.dto.response.FriendRequestListResponse;
import itda.friend.dto.response.FriendRequestResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "Friend Request", description = "친구 요청 관련 API")
@SecurityRequirement(name = "bearerAuth")
public interface FriendRequestSwaggerSupporter {

    @Operation(summary = "친구 요청 생성", description = "친구 요청을 생성하는 API")
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = FriendRequestCreateRequest.class),
            examples = @ExampleObject("""
                    {
                        "targetPetId":20
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "친구 요청 생성 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"친구 요청을 보냈습니다.",
                                "data":{
                                    "requestId":1,
                                    "requesterPet":{
                                        "petId":10,
                                        "publicTag":"pet#TAG1",
                                        "nickname":"pet",
                                        "profileUrl":null,
                                        "verified":true,
                                        "relationship":"NONE"
                                    },
                                    "targetPet":{
                                        "petId":20,
                                        "publicTag":"pet#TAG2",
                                        "nickname":"target",
                                        "profileUrl":null,
                                        "verified":true,
                                        "relationship":"REQUEST_SENT"
                                    },
                                    "status":"PENDING",
                                    "requestedAt":"2026-07-30T00:00:00Z",
                                    "respondedAt":null,
                                    "expiresAt":"2026-08-06T00:00:00Z",
                                    "directRoomId":null
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<FriendRequestResponse>> create(
            @Parameter(hidden = true) CurrentUser currentUser,
            FriendRequestCreateRequest request
    );

    @Operation(summary = "친구 요청 수락", description = "받은 친구 요청을 수락하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "친구 요청 수락 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"친구 요청을 수락했습니다.",
                                "data":{
                                    "requestId":1,
                                    "status":"ACCEPTED",
                                    "respondedAt":"2026-08-05T00:00:00Z",
                                    "directRoomId":100
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<FriendRequestResponse>> accept(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "친구 요청 ID") Long requestId
    );

    @Operation(summary = "친구 요청 거절", description = "받은 친구 요청을 거절하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "친구 요청 거절 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"친구 요청을 거절했습니다.",
                                "data":{
                                    "requestId":1,
                                    "status":"REJECTED",
                                    "respondedAt":"2026-08-05T00:00:00Z",
                                    "directRoomId":null
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<FriendRequestResponse>> reject(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "친구 요청 ID") Long requestId
    );

    @Operation(summary = "친구 요청 취소", description = "보낸 친구 요청을 취소하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "204",
            description = "친구 요청 취소 성공"
    )
    ResponseEntity<Void> cancel(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "친구 요청 ID") Long requestId
    );

    @Operation(summary = "받은 친구 요청 목록 조회", description = "받은 친구 요청 목록을 조회하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "받은 친구 요청 목록 조회 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"받은 친구 요청 목록을 조회했습니다.",
                                "data":{
                                    "items":[
                                        {
                                            "requestId":1,
                                            "status":"PENDING",
                                            "requestedAt":"2026-07-30T00:00:00Z"
                                        }
                                    ],
                                    "nextCursor":null,
                                    "hasNext":false
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<FriendRequestListResponse>> listReceived(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "다음 페이지 조회용 커서") String cursor,
            @Parameter(description = "조회 개수") Integer limit
    );

    @Operation(summary = "보낸 친구 요청 목록 조회", description = "보낸 친구 요청 목록을 조회하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "보낸 친구 요청 목록 조회 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"보낸 친구 요청 목록을 조회했습니다.",
                                "data":{
                                    "items":[
                                        {
                                            "requestId":1,
                                            "status":"PENDING",
                                            "requestedAt":"2026-07-30T00:00:00Z"
                                        }
                                    ],
                                    "nextCursor":null,
                                    "hasNext":false
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<FriendRequestListResponse>> listSent(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "다음 페이지 조회용 커서") String cursor,
            @Parameter(description = "조회 개수") Integer limit
    );
}
