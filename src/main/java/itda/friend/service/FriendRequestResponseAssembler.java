package itda.friend.service;

import itda.friend.domain.FriendRelationship;
import itda.friend.domain.FriendRequest;
import itda.friend.domain.FriendRequestStatus;
import itda.friend.dto.response.FriendRequestPetResponse;
import itda.friend.dto.response.FriendRequestResponse;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class FriendRequestResponseAssembler {

    private final PetDisplayQueryService petDisplayQueryService;

    public FriendRequestResponseAssembler(
            PetDisplayQueryService petDisplayQueryService
    ) {
        this.petDisplayQueryService = petDisplayQueryService;
    }

    public FriendRequestResponse created(
            Snapshot snapshot,
            Long actorPetId
    ) {
        return assemble(
                snapshot,
                relationshipForActor(
                        snapshot.requesterPetId(),
                        actorPetId,
                        FriendRelationship.REQUEST_SENT
                ),
                relationshipForActor(
                        snapshot.targetPetId(),
                        actorPetId,
                        FriendRelationship.REQUEST_SENT
                ),
                null
        );
    }

    public FriendRequestResponse accepted(
            Snapshot snapshot,
            Long actorPetId,
            Long directRoomId
    ) {
        return assemble(
                snapshot,
                relationshipForActor(
                        snapshot.requesterPetId(),
                        actorPetId,
                        FriendRelationship.FRIEND
                ),
                relationshipForActor(
                        snapshot.targetPetId(),
                        actorPetId,
                        FriendRelationship.FRIEND
                ),
                directRoomId
        );
    }

    public FriendRequestResponse rejected(Snapshot snapshot) {
        return assemble(
                snapshot,
                FriendRelationship.NONE,
                FriendRelationship.NONE,
                null
        );
    }

    private FriendRelationship relationshipForActor(
            Long petId,
            Long actorPetId,
            FriendRelationship counterpartRelationship
    ) {
        return petId.equals(actorPetId)
                ? FriendRelationship.NONE
                : counterpartRelationship;
    }

    private FriendRequestResponse assemble(
            Snapshot snapshot,
            FriendRelationship requesterRelationship,
            FriendRelationship targetRelationship,
            Long directRoomId
    ) {
        Map<Long, PetDisplaySummary> pets =
                petDisplayQueryService.getPetDisplaySummaries(
                        List.of(
                                snapshot.requesterPetId(),
                                snapshot.targetPetId()
                        )
                );
        return new FriendRequestResponse(
                snapshot.requestId(),
                FriendRequestPetResponse.from(
                        pets.get(snapshot.requesterPetId()),
                        requesterRelationship
                ),
                FriendRequestPetResponse.from(
                        pets.get(snapshot.targetPetId()),
                        targetRelationship
                ),
                snapshot.status(),
                snapshot.requestedAt(),
                snapshot.respondedAt(),
                snapshot.expiresAt(),
                directRoomId
        );
    }

    public record Snapshot(
            Long requestId,
            Long requesterPetId,
            Long targetPetId,
            FriendRequestStatus status,
            Instant requestedAt,
            Instant respondedAt,
            Instant expiresAt
    ) {

        public static Snapshot from(FriendRequest request) {
            return new Snapshot(
                    request.getId(),
                    request.getRequesterPetId(),
                    request.getTargetPetId(),
                    request.getStatus(),
                    request.getRequestedAt(),
                    request.getRespondedAt(),
                    request.getExpiresAt()
            );
        }
    }
}
