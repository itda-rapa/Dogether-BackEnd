package itda.meetingcard.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.meetingcard.dto.MeetingCardCreateRequest;
import itda.meetingcard.dto.response.MeetingCardResponse;
import itda.meetingcard.service.MeetingCardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/meeting-cards")
@RequiredArgsConstructor
public class MeetingCardController implements MeetingCardSwaggerSupporter {

    private final MeetingCardService meetingCardService;

    /** 약속 카드 확정. 카드·참여자 2건·CARD 메시지가 한 트랜잭션으로 생성된다. */
    @PostMapping
    public ResponseEntity<ApiResponse<MeetingCardResponse>> confirm(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody MeetingCardCreateRequest request
    ) {
        MeetingCardResponse card = meetingCardService.confirm(currentUser.id(), request);
        return ResponseEntity.status(201)
                .body(ApiResponse.created(card, "약속 카드가 생성되었습니다."));
    }

    /** 약속 카드 상세. 카드 참여 Pet 만 조회할 수 있고 아니면 404 로 수렴한다. */
    @GetMapping("/{cardId}")
    public ResponseEntity<ApiResponse<MeetingCardResponse>> get(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long cardId
    ) {
        MeetingCardResponse card = meetingCardService.get(currentUser.id(), cardId);
        return ResponseEntity.ok(ApiResponse.ok(card, "약속 카드 조회 성공"));
    }

    /**
     * 약속 카드 취소. 참여 Pet 양쪽 모두 취소할 수 있고, 이미 취소된 카드는 409 다.
     *
     * <p>취소 시 방에 SYSTEM 메시지 한 건이 함께 생성된다.
     */
    @PostMapping("/{cardId}/cancel")
    public ResponseEntity<ApiResponse<MeetingCardResponse>> cancel(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable long cardId
    ) {
        MeetingCardResponse card = meetingCardService.cancel(currentUser.id(), cardId);
        return ResponseEntity.ok(ApiResponse.ok(card, "약속이 취소되었습니다."));
    }
}
