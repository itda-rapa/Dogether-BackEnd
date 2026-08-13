package itda.chat.controller;

import itda.chat.dto.request.OpenChatRoomCreateRequest;
import itda.chat.dto.request.OpenChatRoomUpdateRequest;
import itda.chat.dto.response.ChatRoomListResponse;
import itda.chat.dto.response.ChatRoomResponse;
import itda.chat.dto.response.OpenChatRoomResponse;
import itda.chat.service.ChatQueryService;
import itda.chat.service.ChatQueryService.ChatRoomListResult;
import itda.chat.service.ChatRoomService;
import itda.common.dto.ApiResponse;
import itda.common.dto.Paging;
import itda.common.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat/rooms")
@RequiredArgsConstructor
public class ChatRoomController implements ChatRoomSwaggerSupporter {

    private final ChatQueryService chatQueryService;
    private final ChatRoomService chatRoomService;

    @GetMapping
    public ResponseEntity<ApiResponse<ChatRoomListResponse>> getRooms(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) Integer limit) {
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

    @PostMapping("/open")
    public ResponseEntity<ApiResponse<OpenChatRoomResponse>> createOpenChatRoom(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody OpenChatRoomCreateRequest request
    ) {
        OpenChatRoomResponse response = chatRoomService.createOpenChatRoom(currentUser.id(), request);
        return ResponseEntity.status(201)
                .body(ApiResponse.created(response, "오픈채팅방 생성 성공"));
    }

    @GetMapping("/open")
    public ResponseEntity<ApiResponse<Page<OpenChatRoomResponse>>> getOpenChatRooms(
            Paging paging
    ) {
        Page<OpenChatRoomResponse> response = chatRoomService.getOpenChatRooms(paging.toPageable());
        return ResponseEntity.ok(ApiResponse.ok(response, "오픈채팅방 목록 조회 성공"));
    }

    @GetMapping("/open/{roomId}")
    public ResponseEntity<ApiResponse<OpenChatRoomResponse>> getOpenChatRoom(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId
    ) {
        OpenChatRoomResponse response = chatRoomService.getOpenChatRoom(currentUser.id(), roomId);
        return ResponseEntity.ok(ApiResponse.ok(response, "오픈채팅방 상세 조회 성공"));
    }

    @PatchMapping("/open/{roomId}")
    public ResponseEntity<ApiResponse<OpenChatRoomResponse>> updateOpenChatRoom(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId,
            @Valid @RequestBody OpenChatRoomUpdateRequest request
    ) {
        OpenChatRoomResponse response = chatRoomService.updateOpenChatRoom(currentUser.id(), roomId, request);
        return ResponseEntity.ok(ApiResponse.ok(response, "오픈채팅방 수정 성공"));
    }

    @DeleteMapping("/open/{roomId}")
    public ResponseEntity<ApiResponse<Void>> deleteOpenChatRoom(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId
    ) {
        chatRoomService.deleteOpenChatRoom(currentUser.id(), roomId);
        return ResponseEntity.ok(ApiResponse.ok("오픈채팅방 삭제 성공"));
    }

    @PostMapping("/open/{roomId}/join")
    public ResponseEntity<ApiResponse<OpenChatRoomResponse>> joinOpenChatRoom(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId
    ) {
        OpenChatRoomResponse response = chatRoomService.joinOpenChatRoom(currentUser.id(), roomId);
        return ResponseEntity.ok(ApiResponse.ok(response, "오픈채팅방 입장 성공"));
    }

    @DeleteMapping("/open/{roomId}/leave")
    public ResponseEntity<ApiResponse<Void>> leaveOpenChatRoom(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId
    ) {
        chatRoomService.leaveOpenChatRoom(currentUser.id(), roomId);
        return ResponseEntity.ok(ApiResponse.ok("오픈채팅방 퇴장 성공"));
    }

}
