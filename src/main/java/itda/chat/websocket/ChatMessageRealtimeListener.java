package itda.chat.websocket;

import itda.chat.event.ChatMessageCommittedEvent;
import itda.chat.domain.RoomType;
import itda.chat.service.ChatRealtimeRecipientQueryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.websocket", name = "enabled", havingValue = "true")
public class ChatMessageRealtimeListener {

    private final ChatRealtimeRecipientQueryService recipientQueryService;
    private final ChatRealtimePublisher publisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageCommitted(ChatMessageCommittedEvent event) {
        if (event.roomType() != RoomType.DIRECT) {
            return;
        }
        long roomId = event.message().roomId();
        ChatMessageCreatedWsEvent payload = new ChatMessageCreatedWsEvent(
                ChatWebSocketEventType.CHAT_MESSAGE_CREATED,
                event.roomType().name(),
                event.message()
        );
        List<Long> recipients;
        try {
            recipients = recipientQueryService.findActiveRecipientUserIds(
                    roomId,
                    event.message().senderPetId()
            );
        } catch (Exception exception) {
            log.warn("Realtime recipient lookup failed roomId={} messageId={} exceptionType={}",
                    roomId, event.message().messageId(), exception.getClass().getSimpleName());
            return;
        }
        for (Long userId : recipients) {
            try {
                publisher.publishToUser(userId, payload);
            } catch (Exception exception) {
                log.warn("Realtime publish failed userId={} roomId={} messageId={} exceptionType={}",
                        userId, roomId, event.message().messageId(), exception.getClass().getSimpleName());
            }
        }
    }
}
