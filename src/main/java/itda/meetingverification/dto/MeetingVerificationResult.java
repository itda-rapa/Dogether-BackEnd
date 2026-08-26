package itda.meetingverification.dto;

/**
 * 위치 제출 처리 결과. M3 응답(04_M3_API_상세명세.md §11)의 {@code counterpartSubmitted}
 * 에 맞춘 내부 계약이다.
 *
 * <p>Meeting 은 확정 시점에만 생성되므로 제출 단계에서는 존재하지 않는다. 제출 상태는
 * {@code submittedPetId} 와 {@code counterpartSubmitted} 로만 표현하고, REST 응답의
 * {@code meetingId} 는 #146 병합 뒤 확정 시점에 채운다.
 */
public record MeetingVerificationResult(
        Long cardId,
        Long submittedPetId,
        boolean counterpartSubmitted
) {
}
