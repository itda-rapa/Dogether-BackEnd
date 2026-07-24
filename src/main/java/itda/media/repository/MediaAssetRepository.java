package itda.media.repository;

import itda.media.domain.MediaAsset;
import itda.media.domain.MediaStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {

    List<MediaAsset> findTop100ByStatusAndExpiresAtBeforeOrderById(
            MediaStatus status,
            Instant expiresAt
    );

    List<MediaAsset> findTop100ByStatusOrderById(MediaStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MediaAsset mediaAsset
               set mediaAsset.status = :expiredStatus
             where mediaAsset.id = :mediaAssetId
               and mediaAsset.status = :pendingStatus
               and mediaAsset.expiresAt <= :now
            """)
    int expirePending(
            @Param("mediaAssetId") Long mediaAssetId,
            @Param("now") Instant now,
            @Param("pendingStatus") MediaStatus pendingStatus,
            @Param("expiredStatus") MediaStatus expiredStatus
    );
}
