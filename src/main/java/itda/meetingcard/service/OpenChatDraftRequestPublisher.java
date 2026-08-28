package itda.meetingcard.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.meetingcard.dto.event.OpenChatDraftRequestEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class OpenChatDraftRequestPublisher {

    public static final String TOPIC = "open-chat-card-draft-request-topic";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publish(OpenChatDraftRequestEvent event) {
        try {
            kafkaTemplate.send(TOPIC, event.roomId().toString(), objectMapper.writeValueAsString(event))
                    .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.OPEN_CHAT_AI_REQUEST_FAILED);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.OPEN_CHAT_AI_REQUEST_FAILED);
        }
    }
}
