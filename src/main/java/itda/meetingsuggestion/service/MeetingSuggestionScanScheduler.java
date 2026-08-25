package itda.meetingsuggestion.service;

import itda.meetingsuggestion.MeetingSuggestionProperties;
import itda.meetingsuggestion.support.SourceDateWindow;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 07:00 Asia/Seoul 에 전날 DIRECT 대화를 분석할 Scan 을 생성한다.
 *
 * <p>이 Scheduler 의 책임은 신규 Scan 생성뿐이다. 처리와 FAILED_RETRYABLE 재처리는
 * {@link MeetingSuggestionRetryWorker} 가 담당한다. 생성은 DB UNIQUE
 * {@code (room_id, source_date)} + {@code ON CONFLICT DO NOTHING} 이라 멱등이며,
 * 여러 인스턴스가 동시에 돌아도 Scan 은 하나만 생긴다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.meeting-suggestion.enabled", havingValue = "true")
public class MeetingSuggestionScanScheduler {

    private final MeetingSuggestionScanClaimService claims;
    private final MeetingSuggestionProperties properties;
    private final Clock clock;

    @Scheduled(cron = "${app.meeting-suggestion.scheduler-cron:0 0 7 * * *}",
            zone = "${app.meeting-suggestion.zone:Asia/Seoul}")
    public void run() {
        try {
            SourceDateWindow window = SourceDateWindow.forRunAt(clock.instant(), properties.zone());
            int created = claims.createScans(window.sourceDate(), window.referenceDate());
            if (created > 0) {
                log.info("Meeting suggestion scans created: sourceDate={}, referenceDate={}, count={}",
                        window.sourceDate(), window.referenceDate(), created);
            }
        } catch (RuntimeException exception) {
            log.error("Meeting suggestion scan creation failed: exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
