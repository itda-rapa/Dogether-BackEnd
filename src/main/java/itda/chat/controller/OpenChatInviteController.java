package itda.chat.controller;

import itda.chat.dto.request.OpenChatInviteRequest;
import itda.chat.dto.response.OpenChatInviteResponse;
import itda.chat.service.OpenChatInviteService;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat/rooms/open")
@RequiredArgsConstructor
public class OpenChatInviteController {

    private final OpenChatInviteService openChatInviteService;

    @PostMapping("/{roomId}/invites")
    public ResponseEntity<ApiResponse<OpenChatInviteResponse>> invite(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId,
            @Valid @RequestBody OpenChatInviteRequest request
    ) {
        OpenChatInviteResponse response = openChatInviteService.invite(
                currentUser.id(), roomId, request.targetPetId());
        return ResponseEntity.ok(ApiResponse.ok(response, "오픈채팅방 초대 성공"));
    }
}
