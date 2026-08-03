package itda.pet.dto;

import itda.friend.domain.FriendRelationship;
import itda.pet.service.query.PetDisplaySummary;

public record PetSearchItemResponse(
        Long petId,
        String publicTag,
        String nickname,
        String profileUrl,
        boolean verified,
        FriendRelationship relationship
) {

    public static PetSearchItemResponse from(
            PetDisplaySummary pet,
            FriendRelationship relationship
    ) {
        return new PetSearchItemResponse(
                pet.petId(),
                pet.publicTag(),
                pet.nickname(),
                pet.profileUrl(),
                pet.verified(),
                relationship
        );
    }
}
