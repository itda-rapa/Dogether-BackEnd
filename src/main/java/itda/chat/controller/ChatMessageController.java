package itda.chat.controller;

import itda.chat.dto.ChatMessageCreateRequest;
import itda.chat.dto.response.ChatMessageListResponse;
import itda.chat.dto.response.ChatMessageResponse;
import itda.chat.service.ChatQueryService;
import itda.chat.service.ChatQueryService.ChatMessageListResult;
import itda.chat.service.ChatQueryService.SendMessageResult;
import itda.common.constants.ErrorCode;
import itda.common.dto.ApiResponse;
import itda.common.exception.BusinessException;
import itda.common.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat/rooms/{roomId}/messages")
@RequiredArgsConstructor
public class ChatMessageController implements ChatMessageSwaggerSupporter {

    private final ChatQueryService chatQueryService;

    @GetMapping
    public ResponseEntity<ApiResponse<ChatMessageListResponse>> getMessages(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId,
            @RequestParam(name = "afterMessageId", required = false, defaultValue = "0") long afterMessageId,
            @RequestParam(name = "limit", required = false) Integer limit) {
        if (afterMessageId < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        ChatMessageListResult result = chatQueryService.getMessages(
                currentUser.id(), roomId, afterMessageId, limit);
        return ResponseEntity.ok(ApiResponse.ok(result.data(), "메시지 목록 조회 성공"));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ChatMessageResponse>> sendMessage(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId,
            @Valid @RequestBody ChatMessageCreateRequest request) {
        SendMessageResult result = chatQueryService.sendMessage(currentUser.id(), roomId, request);
        String message = "메시지 전송 성공";
        if (result.created()) {
            return ResponseEntity.status(201).body(ApiResponse.created(result.data(), message));
        }
        return ResponseEntity.ok(ApiResponse.ok(result.data(), message));
    }
}
