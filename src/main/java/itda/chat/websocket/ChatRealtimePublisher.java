package itda.chat.websocket;

public interface ChatRealtimePublisher {

    void publishToUser(Long userId, ChatMessageCreatedWsEvent event);
}
