package itda.chat.controller;

import itda.chat.dto.response.ChatRoomListResponse;
import itda.chat.dto.response.ChatRoomResponse;
import itda.chat.service.ChatQueryService;
import itda.chat.service.ChatQueryService.ChatRoomListResult;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat/rooms")
@RequiredArgsConstructor
public class ChatRoomController implements ChatRoomSwaggerSupporter {

    private final ChatQueryService chatQueryService;

    @GetMapping
    public ResponseEntity<ApiResponse<ChatRoomListResponse>> getRooms(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        ChatRoomListResult result = chatQueryService.getRooms(currentUser.id(), cursor, limit);
        ChatRoomListResponse body = new ChatRoomListResponse(result.items(), result.page());
        return ResponseEntity.ok(ApiResponse.ok(body, "채팅방 목록 조회 성공"));
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<ApiResponse<ChatRoomResponse>> getRoom(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId
    ) {
        ChatRoomResponse room = chatQueryService.getRoom(currentUser.id(), roomId);
        return ResponseEntity.ok(ApiResponse.ok(room, "채팅방 상세 조회 성공"));
    }
}
