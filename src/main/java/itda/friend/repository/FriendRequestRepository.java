package itda.friend.repository;

import itda.friend.domain.FriendRequest;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FriendRequestRepository extends JpaRepository<FriendRequest, Long> {

    @Query(value = """
            SELECT
                request.id AS requestId,
                request.requester_pet_id AS requesterPetId,
                request.target_pet_id AS targetPetId
            FROM friend_requests request
            WHERE request.id = :requestId
            """, nativeQuery = true)
    Optional<FriendRequestPairRow> findPairById(
            @Param("requestId") Long requestId
    );

    @Query(value = """
            SELECT request.*
            FROM friend_requests request
            WHERE request.id = :requestId
            ORDER BY request.id ASC
            FOR UPDATE
            """, nativeQuery = true)
    Optional<FriendRequest> findByIdForUpdate(
            @Param("requestId") Long requestId
    );

    @Query(value = """
            SELECT request.*
            FROM friend_requests request
            WHERE request.pair_low_id = :petLowId
              AND request.pair_high_id = :petHighId
              AND request.status = 'PENDING'
            ORDER BY request.id ASC
            FOR UPDATE
            """, nativeQuery = true)
    Optional<FriendRequest> findPendingPairForUpdate(
            @Param("petLowId") Long petLowId,
            @Param("petHighId") Long petHighId
    );

    @Query(value = """
            SELECT
                request.id AS requestId,
                request.requester_pet_id AS requesterPetId,
                request.target_pet_id AS targetPetId,
                request.status AS status,
                request.requested_at AS requestedAt,
                request.responded_at AS respondedAt,
                request.expires_at AS expiresAt
            FROM friend_requests request
            WHERE request.target_pet_id = :targetPetId
              AND request.status = 'PENDING'
              AND request.expires_at > :now
              AND (
                  CAST(:cursorRequestedAt AS TIMESTAMPTZ) IS NULL
                  OR request.requested_at < :cursorRequestedAt
                  OR (
                      request.requested_at = :cursorRequestedAt
                      AND request.id < :cursorRequestId
                  )
              )
            ORDER BY request.requested_at DESC, request.id DESC
            LIMIT :limitPlusOne
            """, nativeQuery = true)
    List<FriendRequestListRow> findReceivedPendingPage(
            @Param("targetPetId") Long targetPetId,
            @Param("now") Instant now,
            @Param("cursorRequestedAt") Instant cursorRequestedAt,
            @Param("cursorRequestId") Long cursorRequestId,
            @Param("limitPlusOne") int limitPlusOne
    );

    @Query(value = """
            SELECT
                request.id AS requestId,
                request.requester_pet_id AS requesterPetId,
                request.target_pet_id AS targetPetId,
                request.status AS status,
                request.requested_at AS requestedAt,
                request.responded_at AS respondedAt,
                request.expires_at AS expiresAt
            FROM friend_requests request
            WHERE request.requester_pet_id = :requesterPetId
              AND request.status = 'PENDING'
              AND request.expires_at > :now
              AND (
                  CAST(:cursorRequestedAt AS TIMESTAMPTZ) IS NULL
                  OR request.requested_at < :cursorRequestedAt
                  OR (
                      request.requested_at = :cursorRequestedAt
                      AND request.id < :cursorRequestId
                  )
              )
            ORDER BY request.requested_at DESC, request.id DESC
            LIMIT :limitPlusOne
            """, nativeQuery = true)
    List<FriendRequestListRow> findSentPendingPage(
            @Param("requesterPetId") Long requesterPetId,
            @Param("now") Instant now,
            @Param("cursorRequestedAt") Instant cursorRequestedAt,
            @Param("cursorRequestId") Long cursorRequestId,
            @Param("limitPlusOne") int limitPlusOne
    );

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

    interface FriendRequestPairRow {

        Long getRequestId();

        Long getRequesterPetId();

        Long getTargetPetId();
    }

    interface FriendRequestListRow {

        Long getRequestId();

        Long getRequesterPetId();

        Long getTargetPetId();

        String getStatus();

        Instant getRequestedAt();

        Instant getRespondedAt();

        Instant getExpiresAt();
    }
}
