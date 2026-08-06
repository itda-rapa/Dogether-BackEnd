package itda.meetingcard.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.meetingcard.dto.response.CardDraftResponse;
import itda.meetingcard.service.CardDraftService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat/rooms/{roomId}/card-drafts")
@RequiredArgsConstructor
public class CardDraftController implements CardDraftSwaggerSupporter {

    private final CardDraftService cardDraftService;

    /**
     * AI 약속 카드 초안 생성.
     *
     * <p>AI 실패·지연·컨텍스트 부족은 모두 200 과 빈 폼으로 돌려준다. 요청 Body 는 없고
     * 원본 메시지는 서버가 방에서 직접 읽는다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<CardDraftResponse>> createDraft(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long roomId
    ) {
        CardDraftResponse draft = cardDraftService.createDraft(currentUser.id(), roomId);
        return ResponseEntity.ok(ApiResponse.ok(draft, "약속 카드 초안 생성 성공"));
    }
}
