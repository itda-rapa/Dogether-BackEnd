package itda.chat.websocket;

public record ChatWebSocketErrorPayload(
        String eventType,
        String code,
        String message,
        Long roomId,
        String clientMessageId
) {
}
