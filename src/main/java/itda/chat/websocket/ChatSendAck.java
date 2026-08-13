package itda.chat.websocket;

public record ChatSendAck(
        String eventType,
        Long roomId,
        Long messageId,
        String clientMessageId,
        boolean replayed
) {
}
