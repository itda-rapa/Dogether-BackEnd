package itda.chat.event;

import itda.chat.domain.RoomType;
import itda.chat.dto.response.ChatMessageResponse;

public record ChatMessageCommittedEvent(
        RoomType roomType,
        ChatMessageResponse message
) {
}
