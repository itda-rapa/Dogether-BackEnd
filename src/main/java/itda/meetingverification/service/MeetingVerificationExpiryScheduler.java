package itda.meetingverification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SUBMITTED 만료 worker 를 주기적으로 구동한다. 기본 활성({@code enabled: true})이며,
 * {@code app.meeting-verification.expiry.enabled=false} 는 raw GPS expiry worker 를
 * 명시적으로 비활성화하는 opt-out 이다. 비활성화 시 만료 SUBMITTED raw GPS 가 무기한
 * 보관될 위험이 있으므로 prod 에서는 명시적 결정이 필요하다.
 *
 * <p>실행 간격은 {@code app.meeting-verification.expiry.delay}(기본 {@code 60s})를 사용하고,
 * batchSize 는 {@link MeetingVerificationProperties.Expiry} typed configuration 의 startup
 * 검증을 통과한 값만 사용한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.meeting-verification.expiry.enabled", havingValue = "true")
public class MeetingVerificationExpiryScheduler {

    private final MeetingVerificationExpiryService expiryService;

    @Scheduled(fixedDelayString = "${app.meeting-verification.expiry.delay:60s}")
    public void run() {
        try {
            MeetingVerificationExpiryService.ExpiryResult result = expiryService.runOnce();
            if (result.expired() > 0) {
                log.info("Meeting verification expiry: expired={}", result.expired());
            }
        } catch (RuntimeException exception) {
            log.error("Meeting verification expiry failed", exception);
        }
    }
}
