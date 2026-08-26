package itda.meetingsuggestion.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * retry/처리 worker 주기 실행.
 *
 * <p>주기·lease·backoff 는 {@code app.meeting-suggestion.*} 로 외부화되어 있다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.meeting-suggestion.enabled", havingValue = "true")
public class MeetingSuggestionRetryScheduler {

    private final MeetingSuggestionRetryWorker worker;

    @Scheduled(fixedDelayString = "${app.meeting-suggestion.retry-delay-ms:60000}")
    public void run() {
        try {
            MeetingSuggestionRetryWorker.Result result = worker.runOnce();
            if (result.completed() > 0 || result.retried() > 0
                    || result.failed() > 0 || result.fenced() > 0) {
                log.info("Meeting suggestion worker: completed={}, retried={}, failed={}, fenced={}",
                        result.completed(), result.retried(), result.failed(), result.fenced());
            }
        } catch (RuntimeException exception) {
            log.error("Meeting suggestion worker cycle failed: exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
