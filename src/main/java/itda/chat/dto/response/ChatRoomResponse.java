package itda.chat.dto.response;

import java.time.Instant;

public record ChatRoomResponse(
        Long roomId,
        String status,
        String origin,
        PetSearchItem counterpartPet,
        boolean canSend,
        String sendBlockedReason,
        ChatMessageResponse lastMessage,
        Instant lastMessageAt,
        Instant updatedAt
) {
}