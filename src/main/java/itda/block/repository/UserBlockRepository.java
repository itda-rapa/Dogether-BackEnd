package itda.block.repository;

import itda.block.domain.UserBlock;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

    Optional<UserBlock> findByBlockerUserIdAndBlockedUserId(Long blockerUserId, Long blockedUserId);

    boolean existsByBlockerUserIdAndBlockedUserId(Long blockerUserId, Long blockedUserId);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO user_blocks (
                blocker_user_id,
                blocked_user_id,
                source_pet_id,
                target_pet_id
            ) VALUES (
                :blockerUserId,
                :blockedUserId,
                :sourcePetId,
                :targetPetId
            )
            ON CONFLICT (blocker_user_id, blocked_user_id) DO NOTHING
            """, nativeQuery = true)
    int insertOnConflict(
            @Param("blockerUserId") Long blockerUserId,
            @Param("blockedUserId") Long blockedUserId,
            @Param("sourcePetId") Long sourcePetId,
            @Param("targetPetId") Long targetPetId
    );

    /**
     * Returns true when either direction has a block between the two users.
     */
    @Query("""
            SELECT COUNT(ub) > 0
            FROM UserBlock ub
            WHERE (ub.blockerUserId = :userA AND ub.blockedUserId = :userB)
               OR (ub.blockerUserId = :userB AND ub.blockedUserId = :userA)
            """)
    boolean existsBlockBetween(@Param("userA") Long userA, @Param("userB") Long userB);

    /**
     * Cursor-based list for GET /me/blocks — newest first, (createdAt DESC, id DESC).
     */
    @Query("""
            SELECT ub
            FROM UserBlock ub
            WHERE ub.blockerUserId = :blockerUserId
              AND (
                  CAST(:cursorCreatedAt AS java.time.Instant) IS NULL
                  OR ub.createdAt < :cursorCreatedAt
                  OR (ub.createdAt = :cursorCreatedAt AND ub.id < :cursorId)
              )
            ORDER BY ub.createdAt DESC, ub.id DESC
            """)
    List<UserBlock> findBlocksByBlocker(
            @Param("blockerUserId") Long blockerUserId,
            @Param("cursorCreatedAt") java.time.Instant cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    /**
     * Returns all user IDs that {@code userA} has blocked.
     */
    @Query("SELECT ub.blockedUserId FROM UserBlock ub WHERE ub.blockerUserId = :blockerUserId")
    List<Long> findBlockedUserIdsByBlockerUserId(@Param("blockerUserId") Long blockerUserId);

    @Query("""
            SELECT DISTINCT CASE
                WHEN ub.blockerUserId = :viewerUserId THEN ub.blockedUserId
                ELSE ub.blockerUserId
            END
            FROM UserBlock ub
            WHERE (ub.blockerUserId = :viewerUserId AND ub.blockedUserId IN :authorUserIds)
               OR (ub.blockedUserId = :viewerUserId AND ub.blockerUserId IN :authorUserIds)
            """)
    List<Long> findRelatedUserIds(
            @Param("viewerUserId") Long viewerUserId,
            @Param("authorUserIds") java.util.Collection<Long> authorUserIds
    );
}
