package itda.meetingcard.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.meetingcard.dto.OpenChatPlaceDraftRequest;
import itda.meetingcard.dto.response.OpenChatCardDraftResponse;
import itda.meetingcard.service.OpenChatPlaceDraftService;
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
@RequestMapping("/chat/rooms/open/{roomId}/place-drafts")
@RequiredArgsConstructor
public class OpenChatPlaceDraftController {

    private final OpenChatPlaceDraftService service;

    @PostMapping
    public ResponseEntity<ApiResponse<OpenChatCardDraftResponse>> create(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId,
            @Valid @RequestBody OpenChatPlaceDraftRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                service.create(currentUser.id(), roomId, request),
                "장소 기반 약속 카드 초안 생성 성공"
        ));
    }
}
