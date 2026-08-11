package itda.friend.dto.response;

import itda.friend.domain.FriendRelationship;
import itda.pet.service.query.PetDisplaySummary;

public record FriendRequestPetResponse(
        Long petId,
        String publicTag,
        String nickname,
        String profileUrl,
        boolean verified,
        FriendRelationship relationship
) {

    public static FriendRequestPetResponse from(
            PetDisplaySummary pet,
            FriendRelationship relationship
    ) {
        return new FriendRequestPetResponse(
                pet.petId(),
                pet.publicTag(),
                pet.nickname(),
                pet.profileUrl(),
                pet.verified(),
                relationship
        );
    }
}
