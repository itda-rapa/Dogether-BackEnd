package itda.friend.service.query;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRelationship;
import itda.friend.repository.FriendRequestRepository;
import itda.friend.repository.FriendRequestRepository.PendingFriendRequestRelationshipRow;
import itda.friend.repository.FriendshipRepository;
import itda.friend.repository.FriendshipRepository.FriendshipRelationshipRow;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FriendRelationshipQueryService {

    private final FriendshipRepository friendshipRepository;
    private final FriendRequestRepository friendRequestRepository;
    private final Clock clock;

    @Autowired
    public FriendRelationshipQueryService(
            FriendshipRepository friendshipRepository,
            FriendRequestRepository friendRequestRepository
    ) {
        this(
                friendshipRepository,
                friendRequestRepository,
                Clock.systemUTC()
        );
    }

    FriendRelationshipQueryService(
            FriendshipRepository friendshipRepository,
            FriendRequestRepository friendRequestRepository,
            Clock clock
    ) {
        this.friendshipRepository = friendshipRepository;
        this.friendRequestRepository = friendRequestRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<Long, FriendRelationship> getRelationships(
            Long sourcePetId,
            Collection<Long> targetPetIds
    ) {
        Set<Long> requestedTargetIds = validateAndDistinct(
                sourcePetId,
                targetPetIds
        );
        if (requestedTargetIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, FriendRelationship> relationships = new LinkedHashMap<>();
        requestedTargetIds.forEach(targetPetId ->
                relationships.put(targetPetId, FriendRelationship.NONE)
        );

        Set<Long> queryTargetPetIds = new LinkedHashSet<>(requestedTargetIds);
        queryTargetPetIds.remove(sourcePetId);
        if (queryTargetPetIds.isEmpty()) {
            return Map.copyOf(relationships);
        }

        Instant now = clock.instant();
        List<PendingFriendRequestRelationshipRow> pendingRequests =
                friendRequestRepository.findActivePendingRelationships(
                        sourcePetId,
                        queryTargetPetIds,
                        now
                );
        List<FriendshipRelationshipRow> friendships =
                friendshipRepository.findRelationships(
                        sourcePetId,
                        queryTargetPetIds
                );

        applyPendingRelationships(
                sourcePetId,
                queryTargetPetIds,
                pendingRequests,
                relationships
        );
        applyFriendships(
                sourcePetId,
                queryTargetPetIds,
                friendships,
                relationships
        );

        return Map.copyOf(relationships);
    }

    private Set<Long> validateAndDistinct(
            Long sourcePetId,
            Collection<Long> targetPetIds
    ) {
        if (sourcePetId == null || targetPetIds == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        Set<Long> distinctTargetPetIds = new LinkedHashSet<>();
        for (Long targetPetId : targetPetIds) {
            if (targetPetId == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            distinctTargetPetIds.add(targetPetId);
        }
        return distinctTargetPetIds;
    }

    private void applyPendingRelationships(
            Long sourcePetId,
            Set<Long> queryTargetPetIds,
            List<PendingFriendRequestRelationshipRow> pendingRequests,
            Map<Long, FriendRelationship> relationships
    ) {
        for (PendingFriendRequestRelationshipRow request : pendingRequests) {
            Long requesterPetId = request.getRequesterPetId();
            Long targetPetId = request.getTargetPetId();
            if (sourcePetId.equals(requesterPetId)
                    && queryTargetPetIds.contains(targetPetId)) {
                relationships.put(
                        targetPetId,
                        FriendRelationship.REQUEST_SENT
                );
            } else if (sourcePetId.equals(targetPetId)
                    && queryTargetPetIds.contains(requesterPetId)) {
                relationships.put(
                        requesterPetId,
                        FriendRelationship.REQUEST_RECEIVED
                );
            }
        }
    }

    private void applyFriendships(
            Long sourcePetId,
            Set<Long> queryTargetPetIds,
            List<FriendshipRelationshipRow> friendships,
            Map<Long, FriendRelationship> relationships
    ) {
        for (FriendshipRelationshipRow friendship : friendships) {
            Long counterpartPetId = counterpartPetId(
                    sourcePetId,
                    friendship.getPetLowId(),
                    friendship.getPetHighId()
            );
            if (queryTargetPetIds.contains(counterpartPetId)) {
                relationships.put(
                        counterpartPetId,
                        FriendRelationship.FRIEND
                );
            }
        }
    }

    private Long counterpartPetId(
            Long sourcePetId,
            Long petLowId,
            Long petHighId
    ) {
        if (sourcePetId.equals(petLowId)) {
            return petHighId;
        }
        if (sourcePetId.equals(petHighId)) {
            return petLowId;
        }
        return null;
    }
}
