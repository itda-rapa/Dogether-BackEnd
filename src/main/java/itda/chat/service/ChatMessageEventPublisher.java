package itda.chat.service;

import itda.chat.dto.event.ChatMessageEvent;
import itda.chat.dto.response.ChatMessageResponse;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class ChatMessageEventPublisher {

    private static final String CHAT_MESSAGE_TOPIC = "chat-message-topic";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publishAfterCommit(ChatMessageResponse message) {
        ChatMessageEvent event = ChatMessageEvent.from(message);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(event);
                }
            });
            return;
        }

        publish(event);
    }

    private void publish(ChatMessageEvent event) {
        try {
            kafkaTemplate.send(
                    CHAT_MESSAGE_TOPIC,
                    event.roomId().toString(),
                    objectMapper.writeValueAsString(event)
            );
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.CHAT_MESSAGE_SEND_FAILED);
        }
    }
}
