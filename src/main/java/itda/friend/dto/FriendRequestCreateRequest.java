package itda.friend.dto;

import jakarta.validation.constraints.NotNull;

public record FriendRequestCreateRequest(
        @NotNull Long targetPetId
) {
}
