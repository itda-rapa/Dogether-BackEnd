package itda.chat.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import itda.chat.dto.ChatMessageCreateRequest;
import itda.chat.dto.response.ChatMessageListResponse;
import itda.chat.dto.response.ChatMessageResponse;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "Chat Message", description = "채팅 메시지 관련 API")
@SecurityRequirement(name = "bearerAuth")
public interface ChatMessageSwaggerSupporter {

    @Operation(
            summary = "메시지 목록 조회",
            description = "채팅방 메시지 이력을 조회한다. IMAGE/VIDEO는 attachment를, "
                    + "SETLOG_SHARE는 현재 접근성을 재검증한 sharedSetlog를 함께 반환한다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "메시지 목록 조회 성공",
            useReturnTypeSchema = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"메시지 목록 조회 성공",
                                "data":{
                                    "items":[
                                        {
                                            "messageId":1,
                                            "roomId":10,
                                            "senderType":"PET",
                                            "senderPetId":10,
                                            "senderPetNickname":"도기",
                                            "type":"TEXT",
                                            "body":"안녕하세요",
                                            "attachment":null,
                                            "sharedSetlog":null,
                                            "meetingCardId":null,
                                            "clientMessageId":"client-message-001",
                                            "createdAt":"2026-08-05T00:00:00Z"
                                        }
                                    ],
                                    "nextAfterMessageId":1,
                                    "hasNext":false
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<ChatMessageListResponse>> getMessages(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "채팅방 ID") long roomId,
            @Parameter(description = "이 ID 이후 메시지 조회 (생략 시 최근 메시지)") Long afterMessageId,
            @Parameter(description = "조회 개수") Integer limit
    );

    @Operation(
            summary = "typed 메시지 전송",
            description = "TEXT는 body, IMAGE/VIDEO는 mediaId, SETLOG_SHARE는 setlogId를 사용한다. "
                    + "동일 clientMessageId와 동일 payload 재시도는 기존 메시지를 200으로 반환한다."
    )
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ChatMessageCreateRequest.class),
            examples = @ExampleObject("""
                    {
                        "clientMessageId":"client-message-001",
                        "type":"TEXT",
                        "body":"안녕하세요",
                        "mediaId":null,
                        "setlogId":null
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "메시지 전송 성공",
            useReturnTypeSchema = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"메시지 전송 성공",
                                "data":{
                                    "messageId":1,
                                    "roomId":10,
                                    "senderType":"PET",
                                    "senderPetId":10,
                                    "senderPetNickname":"도기",
                                    "type":"TEXT",
                                    "body":"안녕하세요",
                                    "attachment":null,
                                    "sharedSetlog":null,
                                    "meetingCardId":null,
                                    "clientMessageId":"client-message-001",
                                    "createdAt":"2026-08-05T00:00:00Z"
                                },
                                "error":null
                            }
                            """)
            )
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "동일 clientMessageId의 기존 메시지 반환",
            useReturnTypeSchema = true
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "typed payload 또는 clientMessageId 검증 실패"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Active Pet 또는 전송 리소스 권한 없음"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "채팅방·Media·Setlog를 찾을 수 없음"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "인사 대기·다른 payload의 멱등키 재사용·Media 재사용 충돌"
    )
    ResponseEntity<ApiResponse<ChatMessageResponse>> sendMessage(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "채팅방 ID") long roomId,
            ChatMessageCreateRequest request
    );
}
