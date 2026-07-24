package itda.media.service;

import itda.media.domain.MediaStatus;
import itda.media.repository.MediaAssetRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaStatusService {

    private final MediaAssetRepository mediaAssetRepository;

    public MediaStatusService(MediaAssetRepository mediaAssetRepository) {
        this.mediaAssetRepository = mediaAssetRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expirePending(Long mediaAssetId, Instant now) {
        mediaAssetRepository.expirePending(
                mediaAssetId,
                now,
                MediaStatus.PENDING,
                MediaStatus.EXPIRED
        );
    }
}
