package itda.media.repository;

import itda.media.domain.MediaAsset;
import itda.media.domain.MediaStatus;
import java.time.Instant;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select mediaAsset from MediaAsset mediaAsset where mediaAsset.id = :mediaAssetId")
    java.util.Optional<MediaAsset> findByIdForUpdate(
            @Param("mediaAssetId") Long mediaAssetId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<MediaAsset> findTop100ByStatusAndExpiresAtBeforeOrderById(
            MediaStatus status,
            Instant expiresAt
    );
}
