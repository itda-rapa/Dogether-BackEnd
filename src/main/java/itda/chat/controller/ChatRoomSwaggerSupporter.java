package itda.chat.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import itda.chat.dto.response.ChatRoomListResponse;
import itda.chat.dto.response.ChatRoomResponse;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "Chat Room", description = "채팅방 관련 API")
@SecurityRequirement(name = "bearerAuth")
public interface ChatRoomSwaggerSupporter {

    @Operation(summary = "채팅방 목록 조회", description = "내가 참여 중인 채팅방 목록을 조회하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "채팅방 목록 조회 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"채팅방 목록 조회 성공",
                                "data":{
                                    "items":[
                                        {
                                            "roomId":1,
                                            "roomType":"DIRECT",
                                            "status":"ACTIVE",
                                            "lastMessage":"안녕하세요",
                                            "lastMessageAt":"2026-08-05T00:00:00Z"
                                        }
                                    ],
                                    "page":{
                                        "nextCursor":null,
                                        "hasNext":false
                                    }
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<ChatRoomListResponse>> getRooms(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "다음 페이지 조회용 커서") String cursor,
            @Parameter(description = "조회 개수") Integer limit
    );

    @Operation(summary = "채팅방 상세 조회", description = "채팅방 상세 정보를 조회하는 API")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "채팅방 상세 조회 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject("""
                            {
                                "success":true,
                                "message":"채팅방 상세 조회 성공",
                                "data":{
                                    "roomId":1,
                                    "roomType":"DIRECT",
                                    "status":"ACTIVE",
                                    "participants":[
                                        {
                                            "petId":10,
                                            "nickname":"도기",
                                            "profileUrl":null
                                        }
                                    ]
                                },
                                "error":null
                            }
                            """)
            )
    )
    ResponseEntity<ApiResponse<ChatRoomResponse>> getRoom(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "채팅방 ID") long roomId
    );
}
