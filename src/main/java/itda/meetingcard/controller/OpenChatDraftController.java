package itda.meetingcard.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.meetingcard.dto.response.OpenChatCardDraftResponse;
import itda.meetingcard.service.OpenChatDraftRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat/rooms/open/{roomId}/card-drafts")
@RequiredArgsConstructor
public class OpenChatDraftController {

    private final OpenChatDraftRequestService service;

    @PostMapping
    public ResponseEntity<ApiResponse<List<OpenChatCardDraftResponse>>> requestDraft(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                service.createDrafts(currentUser.id(), roomId),
                "AI 약속 카드 초안 생성 성공"
        ));
    }

    @GetMapping("/{draftId}")
    public ResponseEntity<ApiResponse<OpenChatCardDraftResponse>> getDraft(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId,
            @PathVariable long draftId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                service.getDraft(currentUser.id(), roomId, draftId),
                "AI 약속 카드 초안 조회 성공"
        ));
    }
}
