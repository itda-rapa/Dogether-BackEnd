package itda.meetingverification.dto;

import itda.meetingverification.domain.MeetingVerificationApiStatus;
import itda.meetingverification.domain.MeetingVerificationMethod;
import java.time.Instant;

/**
 * 만남 확인 상태 조회 결과(04_M3_API_상세명세.md §11 GET /meeting-cards/{cardId}/meeting-verification).
 *
 * <p>좌표는 반환하지 않는다. {@code status} 는 사용자용 API 상태다. {@code codeRequired} 는
 * 내 Active Pet 의 제출이 LOW_ACCURACY(CODE_REQUIRED)인지 나타낸다. {@code distanceMeters} 는
 * GPS 확정 Meeting 의 실제 계산 거리이고, 미확정 또는 CODE Meeting 이면 null 이다.
 */
public record MeetingVerificationStatusResponse(
        Long cardId,
        MeetingVerificationApiStatus status,
        boolean mySubmitted,
        boolean counterpartSubmitted,
        Long meetingId,
        boolean confirmed,
        MeetingVerificationMethod verificationMethod,
        Instant confirmedAt,
        boolean codeRequired,
        Double distanceMeters
) {
}
