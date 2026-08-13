package itda.chat.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.websocket", name = "enabled", havingValue = "true")
public class SimpleBrokerChatRealtimePublisher implements ChatRealtimePublisher {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void publishToUser(Long userId, ChatMessageCreatedWsEvent event) {
        messagingTemplate.convertAndSendToUser(
                userId.toString(),
                ChatWebSocketDestinations.CHAT_MESSAGES,
                event
        );
    }
}
