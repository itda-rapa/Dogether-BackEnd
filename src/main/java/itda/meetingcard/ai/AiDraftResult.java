package itda.meetingcard.ai;

import itda.meetingcard.domain.CardDraftFallbackReason;
import itda.meetingcard.domain.MeetingCardType;
import java.time.Instant;

/**
 * AI 추출 결과. 여섯 가지 AI 응답을 모두 예외 없이 표현한다.
 *
 * <p>{@code fallbackReason != null} 이면 AI 측 실패이며 모든 추출 필드는 null 이다.
 * {@code fallbackReason == null} 이면 AI 가 정상 응답했고 cardType / date / time /
 * place / combinedInstant 가 응답 그대로 채워진다(개별 null 가능).
 */
public record AiDraftResult(
        MeetingCardType cardType,
        String date,
        String time,
        String place,
        Instant combinedInstant,
        CardDraftFallbackReason fallbackReason
) {

    /**
     * 모든 추출 필드가 null 인 성공 결과(빈 배열 응답).
     */
    public static AiDraftResult empty() {
        return new AiDraftResult(null, null, null, null, null, null);
    }

    /**
     * fallback 이 발생한 결과.
     */
    public static AiDraftResult fallback(CardDraftFallbackReason reason) {
        return new AiDraftResult(null, null, null, null, null, reason);
    }
}