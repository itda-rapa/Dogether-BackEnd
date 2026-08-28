package itda.meetingcard.consumer;

import itda.meetingcard.dto.event.OpenChatDraftAiResultEvent;
import itda.meetingcard.dto.event.OpenChatDraftReadyEvent;
import itda.meetingcard.service.OpenChatDraftReadyPublisher;
import itda.meetingcard.service.OpenChatDraftResultService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class OpenChatDraftResultConsumer {

    public static final String TOPIC = "open-chat-card-draft-result-topic";

    private final ObjectMapper objectMapper;
    private final OpenChatDraftResultService resultService;
    private final OpenChatDraftReadyPublisher readyPublisher;

    @KafkaListener(
            topics = TOPIC,
            groupId = "${app.meeting-card.open-chat-result-group-id:dogether-open-chat-draft-result}"
    )
    public void consume(String payload) throws Exception {
        OpenChatDraftAiResultEvent event = objectMapper.readValue(
                payload, OpenChatDraftAiResultEvent.class);
        if (event.status() == OpenChatDraftAiResultEvent.Status.FAILED) {
            publish(event, event.requesterUserId(), OpenChatDraftReadyEvent.Status.FAILED,
                    event.message(), List.of());
            return;
        }
        Map<Long, List<OpenChatDraftReadyEvent.Draft>> draftsByUser = resultService.persist(event);
        draftsByUser.forEach((userId, drafts) -> publish(
                event, userId, OpenChatDraftReadyEvent.Status.COMPLETED, null, drafts));
    }

    private void publish(
            OpenChatDraftAiResultEvent source,
            Long targetUserId,
            OpenChatDraftReadyEvent.Status status,
            String message,
            List<OpenChatDraftReadyEvent.Draft> drafts
    ) {
        readyPublisher.publish(new OpenChatDraftReadyEvent(
                source.requestId(), source.roomId(), targetUserId,
                status, message, List.copyOf(drafts)));
    }
}
