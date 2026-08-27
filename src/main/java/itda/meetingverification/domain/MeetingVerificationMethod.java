package itda.meetingverification.domain;

/**
 * 만남 확인 방식. 01_M3_통합_ERD.md §6 {@code meetings.verification_method}.
 *
 * <p>{@code GPS} 는 양쪽 위치 제출로 확정될 때, {@code CODE} 는 확인 코드로 확정될 때
 * 사용한다. 이번 범위에서는 GPS 확정만 구현하고 CODE 발급·입력·검증은 이후 태스크다.
 */
public enum MeetingVerificationMethod {
    GPS,
    CODE
}
