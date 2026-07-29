package itda.setlog.dto;

import itda.friend.domain.FriendRelationship;

public record SetlogAuthorPetResponse(
        Long petId,
        String publicTag,
        String nickname,
        String profileUrl,
        boolean verified,
        FriendRelationship relationship
) {
}
