package itda.media.repository;

import itda.media.domain.MediaAsset;
import itda.media.domain.MediaStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {

    List<MediaAsset> findTop100ByStatusAndExpiresAtBeforeOrderById(
            MediaStatus status,
            Instant expiresAt
    );

    List<MediaAsset> findTop100ByStatusOrderById(MediaStatus status);
}
