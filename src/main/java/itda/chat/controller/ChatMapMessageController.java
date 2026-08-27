package itda.chat.controller;

import itda.chat.dto.request.CreateChatMapMessageRequest;
import itda.chat.dto.request.ShareChatMapLocationRequest;
import itda.chat.dto.response.ChatMessageResponse;
import itda.chat.service.ChatMapMessageService;
import itda.chat.service.ChatMapDistanceService;
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
@RequestMapping("/chat/rooms/{roomId}/map-messages")
@RequiredArgsConstructor
public class ChatMapMessageController {

    private final ChatMapMessageService service;
    private final ChatMapDistanceService distanceService;

    @PostMapping
    public ResponseEntity<ApiResponse<ChatMessageResponse>> create(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId,
            @Valid @RequestBody CreateChatMapMessageRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                service.create(currentUser.id(), roomId, request),
                "지도 메시지가 채팅방에 공유되었습니다."));
    }

    @PostMapping("/{mapMessageId}/locations")
    public ResponseEntity<ApiResponse<ChatMessageResponse>> shareLocation(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId,
            @PathVariable long mapMessageId,
            @Valid @RequestBody ShareChatMapLocationRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                distanceService.shareLocation(currentUser.id(), roomId, mapMessageId, request),
                "참여자 평균 직선거리가 갱신되었습니다."));
    }
}
