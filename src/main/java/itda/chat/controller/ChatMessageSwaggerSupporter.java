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

    @Operation(summary = "메시지 목록 조회", description = "채팅방 메시지 목록을 조회하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "메시지 목록 조회 성공",
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
                                            "senderPetId":10,
                                            "senderPetNickname":"도기",
                                            "type":"TEXT",
                                            "body":"안녕하세요",
                                            "sentAt":"2026-08-05T00:00:00Z"
                                        }
                                    ],
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

    @Operation(summary = "메시지 전송", description = "채팅방에 메시지를 전송하는 API")
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ChatMessageCreateRequest.class),
            examples = @ExampleObject("""
                    {
                        "clientMessageId":"client-message-001",
                        "body":"안녕하세요"
                    }
                    """)
    ))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "메시지 전송 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"메시지 전송 성공",
                                "data":{
                                    "messageId":1,
                                    "senderPetId":10,
                                    "senderPetNickname":"도기",
                                    "type":"TEXT",
                                    "body":"안녕하세요",
                                    "sentAt":"2026-08-05T00:00:00Z"
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<ChatMessageResponse>> sendMessage(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "채팅방 ID") long roomId,
            ChatMessageCreateRequest request
    );
}
