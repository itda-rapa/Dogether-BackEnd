package itda.media.service;

import itda.setlog.service.SetlogUploadCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.storage-cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class StorageCleanupScheduler {
    private final SetlogUploadCleanupService uploadCleanup;
    private final StorageDeleteWorker deleteWorker;
    private final StorageCleanupProperties properties;

    @Scheduled(fixedDelayString = "${app.storage-cleanup.delay-ms:60000}")
    public void run() {
        try {
            int enqueued = uploadCleanup.enqueueExpired(properties.batchSize());
            StorageDeleteWorker.Result result = deleteWorker.runOnce(
                    properties.batchSize(), properties.lease());
            if (enqueued > 0 || result.succeeded() > 0 || result.retried() > 0 || result.failed() > 0) {
                log.info("Storage cleanup: enqueued={}, succeeded={}, retried={}, failed={}",
                        enqueued, result.succeeded(), result.retried(), result.failed());
            }
        } catch (RuntimeException exception) {
            log.error("Storage cleanup failed", exception);
        }
    }
}
