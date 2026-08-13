package itda.setlog.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.StorageDeleteReason;
import itda.media.service.StorageDeleteJobEnqueuer;
import itda.media.service.StorageCleanupProperties;
import itda.setlog.domain.Setlog;
import itda.setlog.domain.SetlogStatus;
import itda.setlog.repository.SetlogRepository;
import itda.setlog.repository.SetlogUploadRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SetlogDeleteService {
    private final SetlogRepository setlogRepository;
    private final StorageDeleteJobEnqueuer deleteJobEnqueuer;
    private final SetlogUploadRepository uploadRepository;
    private final Clock clock;
    private final StorageCleanupProperties cleanupProperties;

    @Transactional
    public void delete(Long ownerUserId, Long setlogId) {
        Setlog setlog = setlogRepository.findByIdForDelete(setlogId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SETLOG_NOT_FOUND));
        if (!setlog.getAuthorPet().getOwner().getId().equals(ownerUserId)) {
            throw new BusinessException(ErrorCode.SETLOG_NOT_FOUND);
        }
        if (setlog.isSeed()) {
            throw new BusinessException(ErrorCode.SEED_SETLOG_DELETE_FORBIDDEN);
        }
        if (setlog.getStatus() == SetlogStatus.DELETED_BY_AUTHOR) {
            throw new BusinessException(ErrorCode.SETLOG_ALREADY_DELETED);
        }

        Instant now = clock.instant();
        setlog.deleteByAuthor();
        setlog.getMedia().markDeleted(now);
        Instant eligibleAt = uploadRepository.findBySetlog_Id(setlogId)
                .map(upload -> later(now, upload.getExpiresAt().plus(cleanupProperties.uploadSettleGrace())))
                .orElse(now);
        deleteJobEnqueuer.enqueue(setlog.getMedia().getPath(),
                setlog.getMedia().getObjectVersionId(),
                StorageDeleteReason.SETLOG_DELETED, eligibleAt);
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }
}
