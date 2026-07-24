package itda.media.service;

import itda.media.domain.MediaAsset;
import itda.media.domain.MediaStatus;
import itda.media.repository.MediaAssetRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Service
public class MediaMaintenanceService {

    private final MediaAssetRepository mediaAssetRepository;
    private final S3StorageService storageService;
    private final Clock clock = Clock.systemUTC();

    public MediaMaintenanceService(
            MediaAssetRepository mediaAssetRepository,
            S3StorageService storageService
    ) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.storageService = storageService;
    }

    @Scheduled(fixedDelayString = "${app.media.maintenance-delay:1m}")
    @Transactional
    public void maintain() {
        expirePendingUploads();
        deleteRequestedAssets();
    }

    private void expirePendingUploads() {
        List<MediaAsset> expired = mediaAssetRepository
                .findTop100ByStatusAndExpiresAtBeforeOrderById(
                        MediaStatus.PENDING,
                        clock.instant()
                );
        for (MediaAsset mediaAsset : expired) {
            tryDelete(mediaAsset.getObjectKey());
            mediaAsset.markExpired();
        }
    }

    private void deleteRequestedAssets() {
        List<MediaAsset> requested = mediaAssetRepository
                .findTop100ByStatusOrderById(MediaStatus.DELETE_REQUESTED);
        for (MediaAsset mediaAsset : requested) {
            try {
                storageService.delete(mediaAsset.getObjectKey());
                mediaAsset.markDeleted();
            } catch (S3Exception ignored) {
                // DELETE_REQUESTED를 유지하여 다음 배치에서 재시도한다.
            }
        }
    }

    private void tryDelete(String objectKey) {
        try {
            storageService.delete(objectKey);
        } catch (S3Exception ignored) {
            // EXPIRED 상태는 확정하고 S3 Lifecycle이 잔여 객체를 보조 정리한다.
        }
    }
}
