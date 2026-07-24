package itda.media.service;

import itda.media.domain.MediaAsset;
import itda.media.domain.MediaStatus;
import itda.media.repository.MediaAssetRepository;
import itda.common.properties.MediaProperties;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Service
public class MediaMaintenanceService {

    private final MediaAssetRepository mediaAssetRepository;
    private final S3StorageService storageService;
    private final MediaProperties properties;
    private final Clock clock;

    @Autowired
    public MediaMaintenanceService(
            MediaAssetRepository mediaAssetRepository,
            S3StorageService storageService,
            MediaProperties properties
    ) {
        this(
                mediaAssetRepository,
                storageService,
                properties,
                Clock.systemUTC()
        );
    }

    MediaMaintenanceService(
            MediaAssetRepository mediaAssetRepository,
            S3StorageService storageService,
            MediaProperties properties,
            Clock clock
    ) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.storageService = storageService;
        this.properties = properties;
        this.clock = clock;
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
                .findTop100ByStatusAndExpiresAtBeforeOrderById(
                        MediaStatus.DELETE_REQUESTED,
                        clock.instant().minus(properties.deleteGrace())
                );
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
