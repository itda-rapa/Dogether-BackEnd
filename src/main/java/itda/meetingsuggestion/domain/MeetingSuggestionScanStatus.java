package itda.meetingsuggestion.domain;

/**
 * 약속 후보 제안 Scan 상태. V41 의 {@code ck_meeting_suggestion_scan_status} 와 일치한다.
 *
 * <p>{@code PENDING -> PROCESSING -> COMPLETED | FAILED_RETRYABLE | FAILED_FINAL} 이며,
 * {@code FAILED_RETRYABLE -> PROCESSING} 재시도와 lease 만료 {@code PROCESSING} 재선점이
 * 허용된다.
 */
public enum MeetingSuggestionScanStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED_RETRYABLE,
    FAILED_FINAL
}
