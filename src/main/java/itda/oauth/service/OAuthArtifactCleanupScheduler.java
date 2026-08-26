package itda.oauth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes expired short-lived OAuth artifacts, including their transient verified email. */
@Component
@RequiredArgsConstructor
@Slf4j
public class OAuthArtifactCleanupScheduler {

    private final OAuthArtifactCleanupService cleanupService;

    @Scheduled(fixedDelayString = "${app.oauth.artifact-cleanup.delay-ms:60000}")
    public void run() {
        try {
            int deleted = cleanupService.deleteExpiredArtifacts();
            if (deleted > 0) {
                log.info("OAuth artifact cleanup: deleted={}", deleted);
            }
        } catch (RuntimeException exception) {
            log.error("OAuth artifact cleanup failed", exception);
        }
    }
}
