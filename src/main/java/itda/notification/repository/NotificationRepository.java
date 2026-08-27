package itda.notification.repository;

import itda.notification.domain.Notification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findTop100ByTargetPetIdOrderByCreatedAtDescIdDesc(Long targetPetId);
    Optional<Notification> findByIdAndTargetPetId(Long id, Long targetPetId);
    long countByTargetPetIdAndReadAtIsNull(Long targetPetId);

    /**
     * Used for reaction notifications. V43's partial unique index makes this
     * idempotent across request retries and concurrent reaction commands.
     */
    @Modifying
    @Query(value = """
            INSERT INTO notifications (
                target_pet_id, actor_pet_id, type, target_type, target_id,
                post_id, setlog_id, actor_pet_nickname_snapshot,
                actor_profile_asset_id_snapshot, comment_preview_snapshot,
                created_at, updated_at
            ) VALUES (
                :recipientPetId, :actorPetId, :type, :targetType, :targetId,
                :postId, :setlogId, :actorNickname, :actorProfileAssetId, :commentPreview,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIgnore(
            @Param("recipientPetId") long recipientPetId,
            @Param("actorPetId") long actorPetId,
            @Param("type") String type,
            @Param("targetType") String targetType,
            @Param("targetId") long targetId,
            @Param("postId") Long postId,
            @Param("setlogId") Long setlogId,
            @Param("actorNickname") String actorNickname,
            @Param("actorProfileAssetId") Long actorProfileAssetId,
            @Param("commentPreview") String commentPreview);
}
