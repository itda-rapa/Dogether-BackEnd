package itda.friend.dto.response;

import itda.friend.domain.FriendRelationship;
import itda.pet.service.query.PetDisplaySummary;

public record FriendPetListItemResponse(
        Long petId,
        String publicTag,
        String nickname,
        String profileUrl,
        boolean verified,
        FriendRelationship relationship
) {

    public static FriendPetListItemResponse from(
            PetDisplaySummary pet,
            FriendRelationship relationship
    ) {
        return new FriendPetListItemResponse(
                pet.petId(),
                pet.publicTag(),
                pet.nickname(),
                pet.profileUrl(),
                pet.verified(),
                relationship
        );
    }
}
