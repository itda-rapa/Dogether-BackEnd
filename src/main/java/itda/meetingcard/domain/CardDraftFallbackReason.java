package itda.meetingcard.domain;

/**
 * 초안이 빈 폼으로 떨어진 사유. V14 의 {@code ck_card_draft_fallback_reason} 과 일치한다.
 *
 * <p>AI 가 정상 동작했으나 약속을 찾지 못한 경우({@code 200 + []})와 일부 필드만 추출한
 * 경우는 fallback 이 아니므로 {@code null} 이다. 이 값이 {@code null} 인지 여부가 곧
 * 응답의 {@code fallback} 플래그이며, 별도 컬럼을 두지 않는다.
 */
public enum CardDraftFallbackReason {
    TIMEOUT,
    MODEL_ERROR,
    INSUFFICIENT_CONTEXT,
    /**
     * AI 가 요청 자체를 거절한 경우(HTTP 422). M3 약속 제안 스케줄러가
     * FAILED_FINAL 분류에 사용한다. 초안 흐름에서는 여전히 빈 폼으로 수렴한다.
     */
    INVALID_REQUEST
}
