package itda.meetingcard.dto.response;

import itda.meetingcard.domain.CardDraft;
import itda.meetingcard.domain.CardDraftFallbackReason;
import itda.meetingcard.domain.MeetingCardType;
import java.time.Instant;

/**
 * OpenAPI {@code CardDraft} 스키마. 단건 객체이며 배열이 아니다.
 *
 * <p>{@code fallback} 은 저장된 컬럼이 아니라 {@code fallbackReason != null} 파생값이다.
 */
public record CardDraftResponse(
        Long draftId,
        Long roomId,
        MeetingCardType cardType,
        String placeText,
        Instant meetAt,
        boolean fallback,
        CardDraftFallbackReason fallbackReason,
        Instant createdAt
) {

    public static CardDraftResponse from(CardDraft draft) {
        return new CardDraftResponse(
                draft.getId(),
                draft.getRoomId(),
                draft.getCardType(),
                draft.getPlaceText(),
                draft.getMeetAt(),
                draft.isFallback(),
                draft.getFallbackReason(),
                draft.getCreatedAt()
        );
    }
}
