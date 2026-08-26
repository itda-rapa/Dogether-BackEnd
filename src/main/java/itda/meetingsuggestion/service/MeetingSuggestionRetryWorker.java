package itda.meetingsuggestion.service;

import itda.meetingsuggestion.MeetingSuggestionProperties;
import itda.meetingsuggestion.service.MeetingSuggestionProcessor.Outcome;
import itda.meetingsuggestion.service.MeetingSuggestionScanClaimService.ClaimedScan;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * claim 후 처리 loop. 07:00 Scan 생성 Scheduler 와 책임이 분리되어 있다.
 *
 * <p>이 worker 가 claim 하는 대상:
 * <ul>
 *   <li>만기된 {@code FAILED_RETRYABLE} — 재시도</li>
 *   <li>lease 만료된 {@code PROCESSING} — stale claim 재선점</li>
 *   <li>{@code PENDING} — 최초 처리(07:00 생성분). Scan 생성 책임은 07:00 Scheduler 가
 *       가지며, 여기서 새 Scan 을 만들지 않는다.</li>
 * </ul>
 *
 * <p>retry 는 새 Scan 을 만들지 않고 기존 Scan 을 다시 PROCESSING 으로 전이시켜 처리한다.
 * 상태 확정은 claimToken 소유권 검증으로 stale worker 의 덮어쓰기를 막는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MeetingSuggestionRetryWorker {

    private final MeetingSuggestionScanClaimService claims;
    private final MeetingSuggestionProcessor processor;
    private final MeetingSuggestionProperties properties;

    public Result runOnce() {
        int completed = 0;
        int retried = 0;
        int failed = 0;
        int fenced = 0;

        for (int processed = 0; processed < properties.batchSize(); processed++) {
            List<ClaimedScan> claimed = claims.claim(1, properties.lease());
            if (claimed.isEmpty()) {
                break;
            }
            ClaimedScan scan = claimed.getFirst();
            if (scan.attempts() > properties.maxAttempts()) {
                if (claims.markFinal(scan, "retry limit exceeded")) {
                    failed++;
                } else {
                    fenced++;
                }
                continue;
            }
            try {
                switch (processor.processOne(scan)) {
                    case COMPLETED -> completed++;
                    case RETRY_SCHEDULED -> retried++;
                    case FAILED_FINAL -> failed++;
                    case FENCED -> fenced++;
                }
            } catch (RuntimeException exception) {
                // 예기치 못한 실패(DB 장애 등)는 상태를 건드리지 않는다. Scan 은
                // PROCESSING 으로 남고 lease 만료 후 다시 claim 된다.
                log.warn("Meeting suggestion processing failed, scan stays PROCESSING for lease recovery: scanId={}, attempts={}, exceptionType={}",
                        scan.id(), scan.attempts(), exception.getClass().getSimpleName());
            }
        }
        return new Result(completed, retried, failed, fenced);
    }

    public record Result(int completed, int retried, int failed, int fenced) {
    }
}
