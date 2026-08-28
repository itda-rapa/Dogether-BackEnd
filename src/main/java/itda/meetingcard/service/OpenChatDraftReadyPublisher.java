package itda.meetingcard.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.meetingcard.dto.event.OpenChatDraftReadyEvent;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class OpenChatDraftReadyPublisher {

    public static final String TOPIC = "open-chat-card-draft-notification-topic";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publish(OpenChatDraftReadyEvent event) {
        try {
            kafkaTemplate.send(
                    TOPIC,
                    event.targetUserId().toString(),
                    objectMapper.writeValueAsString(event)
            ).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.OPEN_CHAT_AI_REQUEST_FAILED);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.OPEN_CHAT_AI_REQUEST_FAILED);
        }
    }
}
