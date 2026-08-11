package itda.friend.dto.response;

import itda.friend.domain.FriendRequestStatus;
import java.time.Instant;

public record FriendRequestResponse(
        Long requestId,
        FriendRequestPetResponse requesterPet,
        FriendRequestPetResponse targetPet,
        FriendRequestStatus status,
        Instant requestedAt,
        Instant respondedAt,
        Instant expiresAt,
        Long directRoomId
) {
}
