package itda.meetingverification.domain;

/**
 * 위치 제출 한 건의 상태. 01_M3_통합_ERD.md §6 {@code meeting_verifications.status}.
 *
 * <p>{@code SUBMITTED} 는 ACCEPTABLE 위치, {@code CODE_REQUIRED} 는 LOW_ACCURACY 위치를
 * 나타낸다. {@code ACCEPTED/REJECTED} 는 M3 계약에 예약된 값으로 아직 사용하지 않는다.
 */
public enum MeetingVerificationStatus {
    SUBMITTED,
    CODE_REQUIRED,
    ACCEPTED,
    REJECTED
}
