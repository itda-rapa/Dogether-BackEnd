package itda.meetingcard.ai;

import itda.meetingcard.domain.CardDraftFallbackReason;
import itda.meetingcard.domain.MeetingCardType;
import java.time.Instant;
import java.util.List;

/**
 * AI 추출 결과. 여섯 가지 AI 응답을 모두 예외 없이 표현한다.
 *
 * <p>{@code fallbackReason != null} 이면 AI 측 실패이며 blank 후보 하나를 가진다.
 * {@code fallbackReason == null} 이면 AI 가 정상 응답했고 후보 목록을 원래 순서대로
 * 가진다. 후보 내부 필드는 부분 추출에 따라 개별적으로 null 일 수 있다.
 */
public record AiDraftResult(
        List<Candidate> candidates,
        CardDraftFallbackReason fallbackReason
) {

    public AiDraftResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public record Candidate(
            MeetingCardType cardType,
            String date,
            String time,
            String place,
            Instant combinedInstant
    ) {
        public static Candidate blank() {
            return new Candidate(null, null, null, null, null);
        }
    }

    public static AiDraftResult success(List<Candidate> candidates) {
        return new AiDraftResult(candidates, null);
    }

    /** 기존 단건 소비자와의 호환을 위한 첫 후보 접근자. */
    public MeetingCardType cardType() {
        return first().cardType();
    }

    public String date() {
        return first().date();
    }

    public String time() {
        return first().time();
    }

    public String place() {
        return first().place();
    }

    public Instant combinedInstant() {
        return first().combinedInstant();
    }

    private Candidate first() {
        return candidates.isEmpty() ? Candidate.blank() : candidates.get(0);
    }

    /**
     * 모든 추출 필드가 null 인 성공 후보 하나(빈 배열 응답).
     */
    public static AiDraftResult empty() {
        return success(List.of(Candidate.blank()));
    }

    /**
     * fallback 이 발생한 결과.
     */
    public static AiDraftResult fallback(CardDraftFallbackReason reason) {
        return new AiDraftResult(List.of(Candidate.blank()), reason);
    }
}
