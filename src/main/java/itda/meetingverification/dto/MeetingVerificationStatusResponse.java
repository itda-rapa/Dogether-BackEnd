package itda.meetingverification.dto;

import itda.meetingverification.domain.MeetingVerificationMethod;
import java.time.Instant;

/**
 * 만남 확인 상태 조회 결과(04_M3_API_상세명세.md §11 GET /meeting-cards/{cardId}/meeting-verification).
 *
 * <p>좌표는 반환하지 않는다. {@code codeRequired} 는 내 Active Pet 의 제출이 LOW_ACCURACY
 * (CODE_REQUIRED)인지 나타낸다.
 */
public record MeetingVerificationStatusResponse(
        Long cardId,
        boolean mySubmitted,
        boolean counterpartSubmitted,
        Long meetingId,
        boolean confirmed,
        MeetingVerificationMethod verificationMethod,
        Instant confirmedAt,
        boolean codeRequired
) {
}
