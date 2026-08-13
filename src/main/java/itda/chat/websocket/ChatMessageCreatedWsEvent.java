package itda.chat.websocket;

import itda.chat.dto.response.ChatMessageResponse;

public record ChatMessageCreatedWsEvent(
        String eventType,
        String roomType,
        ChatMessageResponse message
) {
}
