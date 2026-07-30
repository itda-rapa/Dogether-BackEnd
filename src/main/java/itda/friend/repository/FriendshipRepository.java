package itda.friend.repository;

import itda.friend.domain.Friendship;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FriendshipRepository extends JpaRepository<Friendship, Long> {

    boolean existsByPetLowIdAndPetHighId(
            Long petLowId,
            Long petHighId
    );

    @Query(value = """
            SELECT
                relationship.pet_id AS petId,
                COUNT(*) AS friendCount
            FROM (
                SELECT friendship.pet_low_id AS pet_id
                FROM friendships friendship
                WHERE friendship.pet_low_id IN (:petIds)
                UNION ALL
                SELECT friendship.pet_high_id AS pet_id
                FROM friendships friendship
                WHERE friendship.pet_high_id IN (:petIds)
            ) relationship
            GROUP BY relationship.pet_id
            """, nativeQuery = true)
    List<FriendshipCountRow> countRelationshipsByPetIds(
            @Param("petIds") Collection<Long> petIds
    );

    @Query(value = """
            SELECT
                friendship.pet_low_id AS petLowId,
                friendship.pet_high_id AS petHighId
            FROM friendships friendship
            WHERE (
                friendship.pet_low_id = :sourcePetId
                AND friendship.pet_high_id IN (:targetPetIds)
            ) OR (
                friendship.pet_high_id = :sourcePetId
                AND friendship.pet_low_id IN (:targetPetIds)
            )
            """, nativeQuery = true)
    List<FriendshipRelationshipRow> findRelationships(
            @Param("sourcePetId") Long sourcePetId,
            @Param("targetPetIds") Collection<Long> targetPetIds
    );

    @Query(value = """
            SELECT
                friendship.id AS friendshipId,
                friendship.created_at AS createdAt,
                CASE
                    WHEN friendship.pet_low_id = :petId
                        THEN friendship.pet_high_id
                    ELSE friendship.pet_low_id
                END AS counterpartPetId
            FROM friendships friendship
            WHERE (
                    friendship.pet_low_id = :petId
                    OR friendship.pet_high_id = :petId
                  )
              AND (
                    CAST(:cursorCreatedAt AS TIMESTAMPTZ) IS NULL
                    OR friendship.created_at < :cursorCreatedAt
                    OR (
                        friendship.created_at = :cursorCreatedAt
                        AND friendship.id < :cursorFriendshipId
                    )
                  )
            ORDER BY friendship.created_at DESC, friendship.id DESC
            LIMIT :limitPlusOne
            """, nativeQuery = true)
    List<FriendshipListRow> findFriendPage(
            @Param("petId") Long petId,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorFriendshipId") Long cursorFriendshipId,
            @Param("limitPlusOne") int limitPlusOne
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM friendships friendship
             USING pets low_pet, pets high_pet
             WHERE low_pet.id = friendship.pet_low_id
               AND high_pet.id = friendship.pet_high_id
               AND (
                   (low_pet.owner_user_id = :userA
                       AND high_pet.owner_user_id = :userB)
                   OR
                   (low_pet.owner_user_id = :userB
                       AND high_pet.owner_user_id = :userA)
               )
            """, nativeQuery = true)
    int deleteBetweenUsers(
            @Param("userA") Long userA,
            @Param("userB") Long userB
    );

    interface FriendshipRelationshipRow {

        Long getPetLowId();

        Long getPetHighId();
    }

    interface FriendshipCountRow {

        Long getPetId();

        Long getFriendCount();
    }

    interface FriendshipListRow {

        Long getFriendshipId();

        Instant getCreatedAt();

        Long getCounterpartPetId();
    }
}
