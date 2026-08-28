package itda.chat.controller;

import itda.chat.dto.request.OpenChatRoomCreateRequest;
import itda.chat.dto.request.OpenChatRoomUpdateRequest;
import itda.chat.dto.response.OpenChatRoomResponse;
import itda.chat.service.ChatRoomService;
import itda.meetingcard.dto.response.OpenChatDraftParticipantResponse;
import itda.common.dto.ApiResponse;
import itda.common.dto.Paging;
import itda.common.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat/rooms/open")
@RequiredArgsConstructor
public class OpenChatController {

    private final ChatRoomService chatRoomService;

    @PostMapping
    public ResponseEntity<ApiResponse<OpenChatRoomResponse>> createOpenChatRoom(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody OpenChatRoomCreateRequest request
    ) {
        OpenChatRoomResponse response = chatRoomService.createOpenChatRoom(currentUser.id(), request);
        return ResponseEntity.status(201)
                .body(ApiResponse.created(response, "오픈채팅방 생성 성공"));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<OpenChatRoomResponse>>> getOpenChatRooms(
            Paging paging
    ) {
        Page<OpenChatRoomResponse> response = chatRoomService.getOpenChatRooms(paging.toPageable());
        return ResponseEntity.ok(ApiResponse.ok(response, "오픈채팅방 목록 조회 성공"));
    }

    @GetMapping("/joined")
    public ResponseEntity<ApiResponse<List<OpenChatRoomResponse>>> getJoinedOpenChatRooms(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        List<OpenChatRoomResponse> response = chatRoomService.getJoinedOpenChatRooms(currentUser.id());
        return ResponseEntity.ok(ApiResponse.ok(response, "참여 중인 오픈채팅방 목록 조회 성공"));
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<ApiResponse<OpenChatRoomResponse>> getOpenChatRoom(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId
    ) {
        OpenChatRoomResponse response = chatRoomService.getOpenChatRoom(currentUser.id(), roomId);
        return ResponseEntity.ok(ApiResponse.ok(response, "오픈채팅방 상세 조회 성공"));
    }

    @GetMapping("/{roomId}/participants")
    public ResponseEntity<ApiResponse<List<OpenChatDraftParticipantResponse>>> getOpenChatParticipants(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                chatRoomService.getOpenChatParticipants(currentUser.id(), roomId),
                "오픈채팅방 참여자 목록 조회 성공"));
    }

    @PatchMapping("/{roomId}")
    public ResponseEntity<ApiResponse<OpenChatRoomResponse>> updateOpenChatRoom(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId,
            @Valid @RequestBody OpenChatRoomUpdateRequest request
    ) {
        OpenChatRoomResponse response = chatRoomService.updateOpenChatRoom(currentUser.id(), roomId, request);
        return ResponseEntity.ok(ApiResponse.ok(response, "오픈채팅방 수정 성공"));
    }

    @DeleteMapping("/{roomId}")
    public ResponseEntity<ApiResponse<Void>> deleteOpenChatRoom(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId
    ) {
        chatRoomService.deleteOpenChatRoom(currentUser.id(), roomId);
        return ResponseEntity.ok(ApiResponse.ok("오픈채팅방 삭제 성공"));
    }

    @PostMapping("/{roomId}/join")
    public ResponseEntity<ApiResponse<OpenChatRoomResponse>> joinOpenChatRoom(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId
    ) {
        OpenChatRoomResponse response = chatRoomService.joinOpenChatRoom(currentUser.id(), roomId);
        return ResponseEntity.ok(ApiResponse.ok(response, "오픈채팅방 입장 성공"));
    }

    @DeleteMapping("/{roomId}/leave")
    public ResponseEntity<ApiResponse<Void>> leaveOpenChatRoom(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId
    ) {
        chatRoomService.leaveOpenChatRoom(currentUser.id(), roomId);
        return ResponseEntity.ok(ApiResponse.ok("오픈채팅방 퇴장 성공"));
    }
}
