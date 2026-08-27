package itda.chat.controller;

import itda.chat.dto.request.PlaceIntentRequest;
import itda.chat.dto.response.PlaceIntentResponse;
import itda.chat.service.PlaceIntentService;
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
@RequestMapping("/chat/rooms/{roomId}/place-intent")
@RequiredArgsConstructor
public class PlaceIntentController {

    private final PlaceIntentService service;

    @PostMapping
    public ResponseEntity<ApiResponse<PlaceIntentResponse>> decide(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId,
            @Valid @RequestBody PlaceIntentRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                service.decide(currentUser.id(), roomId, request.triggerMessageId()),
                "장소 탐색 팝업 판단 성공"));
    }
}
