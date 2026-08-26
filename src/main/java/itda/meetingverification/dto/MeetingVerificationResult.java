package itda.meetingverification.dto;

import itda.meetingverification.domain.MeetingVerificationApiStatus;
import itda.meetingverification.domain.MeetingVerificationMethod;
import java.time.Instant;

/**
 * 위치 제출 처리 결과.
 *
 * <p>{@code status} 는 사용자용 API 상태이고 {@code codeRequired}/{@code confirmed}/
 * {@code verificationMethod}/{@code confirmedAt}/{@code distanceMeters} 의 의미는 GET status
 * 와 같다. {@code confirmed=false} 이면 Meeting 관련 값(meetingId·verificationMethod·
 * confirmedAt·distanceMeters)은 null 이다.
 */
public record MeetingVerificationResult(
        Long cardId,
        Long submittedPetId,
        MeetingVerificationApiStatus status,
        boolean counterpartSubmitted,
        Long meetingId,
        boolean confirmed,
        MeetingVerificationMethod verificationMethod,
        Instant confirmedAt,
        boolean codeRequired,
        Double distanceMeters
) {
}
