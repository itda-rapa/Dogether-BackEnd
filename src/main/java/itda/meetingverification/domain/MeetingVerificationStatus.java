package itda.meetingverification.domain;

/**
 * 위치 제출 한 건의 상태. 01_M3_통합_ERD.md §6 {@code meeting_verifications.status}.
 *
 * <p>현재는 {@code SUBMITTED} 만 기록하고, {@code CODE_REQUIRED/ACCEPTED/REJECTED} 는
 * #146 Location 병합 뒤 판정에서 설정한다.
 */
public enum MeetingVerificationStatus {
    SUBMITTED,
    CODE_REQUIRED,
    ACCEPTED,
    REJECTED
}
