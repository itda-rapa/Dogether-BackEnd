package itda.meetingverification.domain;

/**
 * 만남 확인 방식. 01_M3_통합_ERD.md §6 {@code meetings.verification_method}.
 *
 * <p>GPS / Confirmation Code 구분을 수용하는 설계만 둔다. 값은 #146 Location 병합 뒤
 * 확정 시 채우며, CODE 발급·입력·검증 기능은 이후 태스크에서 구현한다.
 */
public enum MeetingVerificationMethod {
    GPS,
    CODE
}
