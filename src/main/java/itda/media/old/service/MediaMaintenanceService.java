package itda.media.old.service;

//import itda.media.old.domain.MediaAsset;
//import itda.media.domain.MediaStatus;
//import itda.media.repository.MediaAssetRepository;
//import java.time.Clock;
//import java.util.List;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//import software.amazon.awssdk.core.exception.SdkException;
//
//@Slf4j
//@Service
//public class MediaMaintenanceService {
//
//    private final MediaAssetRepository mediaAssetRepository;
//    private final S3StorageService storageService;
//    private final Clock clock;
//
//    @Autowired
//    public MediaMaintenanceService(
//            MediaAssetRepository mediaAssetRepository,
//            S3StorageService storageService
//    ) {
//        this(
//                mediaAssetRepository,
//                storageService,
//                Clock.systemUTC()
//        );
//    }
//
//    MediaMaintenanceService(
//            MediaAssetRepository mediaAssetRepository,
//            S3StorageService storageService,
//            Clock clock
//    ) {
//        this.mediaAssetRepository = mediaAssetRepository;
//        this.storageService = storageService;
//        this.clock = clock;
//    }
//
//    @Scheduled(fixedDelayString = "${app.media.maintenance-delay:1m}")
//    @Transactional
//    public void maintain() {
//        expirePendingUploads();
//        deleteRequestedAssets();
//    }
//
//    private void expirePendingUploads() {
//        List<MediaAsset> expired = mediaAssetRepository
//                .findTop100ByStatusAndExpiresAtBeforeOrderById(
//                        MediaStatus.PENDING,
//                        clock.instant()
//                );
//        for (MediaAsset mediaAsset : expired) {
//            try {
//                storageService.delete(mediaAsset.getObjectKey());
//                mediaAsset.markExpired();
//            } catch (SdkException exception) {
//                logDeleteFailure("expired upload", mediaAsset, exception);
//            }
//        }
//    }
//
//    private void deleteRequestedAssets() {
//        List<MediaAsset> requested = mediaAssetRepository
//                .findTop100ByStatusOrderById(MediaStatus.DELETE_REQUESTED);
//        for (MediaAsset mediaAsset : requested) {
//            try {
//                storageService.delete(mediaAsset.getObjectKey());
//                mediaAsset.markDeleted();
//            } catch (SdkException exception) {
//                logDeleteFailure("requested deletion", mediaAsset, exception);
//            }
//        }
//    }
//
//    private void logDeleteFailure(
//            String operation,
//            MediaAsset mediaAsset,
//            SdkException exception
//    ) {
//        log.warn(
//                "S3 {} failed; it will be retried. mediaAssetId={}, objectKey={}, message={}",
//                operation,
//                mediaAsset.getId(),
//                mediaAsset.getObjectKey(),
//                exception.getMessage()
//        );
//    }
//}
