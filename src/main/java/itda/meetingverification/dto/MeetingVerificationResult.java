package itda.meetingverification.dto;

import itda.meetingverification.domain.MeetingVerificationMethod;
import java.time.Instant;

/**
 * 위치 제출 처리 결과.
 *
 * <p>첫 제출·LOW_ACCURACY 는 {@code confirmed=false}, Meeting 관련 값(meetingId·
 * verificationMethod·confirmedAt)은 null 이다. GPS 확정 또는 GPS 경합 재조회는 실제
 * Meeting 정보를 반환한다.
 */
public record MeetingVerificationResult(
        Long cardId,
        Long submittedPetId,
        boolean counterpartSubmitted,
        Long meetingId,
        boolean confirmed,
        MeetingVerificationMethod verificationMethod,
        Instant confirmedAt
) {
}
