package itda.friend.repository;

import itda.friend.domain.FriendRequest;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FriendRequestRepository extends JpaRepository<FriendRequest, Long> {

    @Query(value = """
            SELECT
                request.requester_pet_id AS requesterPetId,
                request.target_pet_id AS targetPetId
            FROM friend_requests request
            WHERE request.status = 'PENDING'
              AND request.expires_at > :now
              AND (
                  (
                      request.requester_pet_id = :sourcePetId
                      AND request.target_pet_id IN (:targetPetIds)
                  ) OR (
                      request.target_pet_id = :sourcePetId
                      AND request.requester_pet_id IN (:targetPetIds)
                  )
              )
            """, nativeQuery = true)
    List<PendingFriendRequestRelationshipRow> findActivePendingRelationships(
            @Param("sourcePetId") Long sourcePetId,
            @Param("targetPetIds") Collection<Long> targetPetIds,
            @Param("now") Instant now
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE friend_requests request
               SET status = 'CANCELED',
                   responded_at = CURRENT_TIMESTAMP,
                   updated_at = CURRENT_TIMESTAMP
             WHERE request.status = 'PENDING'
               AND EXISTS (
                   SELECT 1
                     FROM pets requester_pet, pets target_pet
                    WHERE requester_pet.id = request.requester_pet_id
                      AND target_pet.id = request.target_pet_id
                      AND (
                          (requester_pet.owner_user_id = :userA
                              AND target_pet.owner_user_id = :userB)
                          OR
                          (requester_pet.owner_user_id = :userB
                              AND target_pet.owner_user_id = :userA)
                      )
               )
            """, nativeQuery = true)
    int cancelPendingBetweenUsers(
            @Param("userA") Long userA,
            @Param("userB") Long userB
    );

    interface PendingFriendRequestRelationshipRow {

        Long getRequesterPetId();

        Long getTargetPetId();
    }
}
