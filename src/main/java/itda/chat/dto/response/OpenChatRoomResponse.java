package itda.chat.dto.response;

import itda.chat.domain.ChatRoom;
import java.time.Instant;

public record OpenChatRoomResponse(
        Long roomId,
        String title,
        String description,
        Long ownerPetId,
        Integer maxParticipants,
        Boolean isPublic,
        String status,
        String origin,
        Instant createdAt,
        Instant updatedAt
) {

    public static OpenChatRoomResponse from(ChatRoom room) {
        return new OpenChatRoomResponse(
                room.getId(),
                room.getTitle(),
                room.getDescription(),
                room.getOwnerPetId(),
                room.getMaxParticipants(),
                room.getIsPublic(),
                room.getStatus().name(),
                room.getOrigin().name(),
                room.getCreatedAt(),
                room.getUpdatedAt()
        );
    }
}
