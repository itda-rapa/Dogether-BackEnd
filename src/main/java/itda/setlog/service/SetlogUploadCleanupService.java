package itda.setlog.service;

import itda.media.domain.StorageDeleteReason;
import itda.media.service.StorageDeleteJobEnqueuer;
import itda.media.service.StorageCleanupProperties;
import itda.setlog.domain.SetlogUpload;
import itda.setlog.domain.SetlogUploadStatus;
import itda.setlog.repository.SetlogUploadRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SetlogUploadCleanupService {
    private final SetlogUploadRepository uploadRepository;
    private final StorageDeleteJobEnqueuer deleteJobEnqueuer;
    private final Clock clock;
    private final StorageCleanupProperties cleanupProperties;

    @Transactional
    public int enqueueExpired(int batchSize) {
        Instant now = clock.instant();
        List<SetlogUpload> candidates = uploadRepository.findCleanupCandidatesForUpdate(now, batchSize);
        for (SetlogUpload upload : candidates) {
            SetlogUploadStatus previous = upload.getStatus();
            if (previous == SetlogUploadStatus.PRESIGNED) {
                upload.expire();
            }
            Instant eligibleAt = upload.getExpiresAt().plus(cleanupProperties.uploadSettleGrace());
            String retainedVersionId = previous == SetlogUploadStatus.COMPLETED
                    ? upload.getMedia().getObjectVersionId() : null;
            if (previous == SetlogUploadStatus.COMPLETED
                    && (retainedVersionId == null || retainedVersionId.isBlank())) {
                // Local/test storage may complete without Bucket Versioning. It
                // has no safe retained version to prune, so do not block other jobs.
                continue;
            }
            deleteJobEnqueuer.enqueue(upload.getObjectKey(), retainedVersionId, reason(previous),
                    eligibleAt.isAfter(now) ? eligibleAt : now);
        }
        return candidates.size();
    }

    private static StorageDeleteReason reason(SetlogUploadStatus status) {
        return switch (status) {
            case COMPLETED -> StorageDeleteReason.UPLOAD_SURPLUS_VERSIONS;
            case PRESIGNED -> StorageDeleteReason.UPLOAD_EXPIRED;
            case EXPIRED -> StorageDeleteReason.UPLOAD_EXPIRED;
            case REJECTED -> StorageDeleteReason.UPLOAD_REJECTED;
            case CANCELED -> StorageDeleteReason.UPLOAD_CANCELED;
            default -> throw new IllegalArgumentException("Status is not cleanable");
        };
    }
}
