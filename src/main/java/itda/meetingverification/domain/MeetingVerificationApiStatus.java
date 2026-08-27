package itda.meetingverification.domain;

import itda.meetingverification.dto.MeetingVerificationResult;

/**
 * 만남 확인의 사용자용(API) 상태. 영속 상태 {@link MeetingVerificationStatus} 와 다르므로
 * 별도 enum 으로 두고 {@link itda.meetingverification.service.MeetingVerificationService} 가
 * 일관된 snapshot 에서 매핑한다.
 *
 * <p>POST 응답({@link MeetingVerificationResult})과 GET status 응답이 같은 의미로 이 값을
 * 사용한다.
 */
public enum MeetingVerificationApiStatus {
    /** 아직 내 제출이 없다. */
    NOT_SUBMITTED,
    /** 내 제출은 있고 상대 확정을 기다리는 중이다. */
    WAITING_COUNTERPART,
    /** 양쪽 GPS 제출로 Meeting 이 확정되었다. */
    GPS_CONFIRMED,
    /** 내 제출이 LOW_ACCURACY 여서 확인 코드가 필요하다. */
    CODE_REQUIRED,
    /** 확인 코드로 Meeting 이 확정되었다(이번 범위 이후 사용). */
    CODE_CONFIRMED,
    /** 내 제출이 거부되었다(이번 범위에서 사용하지 않는다). */
    REJECTED,
    /** 약속 시간창이 지나 GPS 확정 없이 만료되었다. */
    EXPIRED
}
