package itda.meetingverification.domain;

/**
 * 위치 제출 한 건의 영속 상태. 01_M3_통합_ERD.md §6 {@code meeting_verifications.status}.
 *
 * <p>영속 상태는 API 표현 상태({@link MeetingVerificationApiStatus})와 다르다.
 * <ul>
 *   <li>{@code SUBMITTED}: ACCEPTABLE 위치 제출. raw 좌표가 보관되며 GPS 판정 대상이다.</li>
 *   <li>{@code CODE_REQUIRED}: LOW_ACCURACY 위치 제출. raw 좌표는 즉시 scrub 된다.</li>
 *   <li>{@code ACCEPTED}: GPS 확정으로 양쪽 제출이 확정된 상태. raw 좌표는 scrub 된다.</li>
 *   <li>{@code REJECTED}: 거부된 제출(이번 범위에서 사용하지 않는다).</li>
 *   <li>{@code EXPIRED}: 약속 시간창이 지나 GPS 확정 없이 만료된 제출. raw 좌표는 scrub 된다.</li>
 * </ul>
 */
public enum MeetingVerificationStatus {
    SUBMITTED,
    CODE_REQUIRED,
    ACCEPTED,
    REJECTED,
    EXPIRED
}
