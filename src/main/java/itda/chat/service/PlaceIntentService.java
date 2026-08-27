package itda.chat.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import itda.chat.domain.ChatMessage;
import itda.chat.domain.ChatRoomParticipant;
import itda.chat.domain.MessageType;
import itda.chat.domain.SenderType;
import itda.chat.dto.response.PlaceIntentResponse;
import itda.chat.repository.ChatMessageRepository;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class PlaceIntentService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MAX_CONTEXT_MESSAGES = 3;

    private final ActivePetQueryService activePetQueryService;
    private final ChatQueryService chatQueryService;
    private final ChatMessageRepository messageRepository;
    private final ChatRoomParticipantRepository participantRepository;
    private final RestClient aiClient;

    @Autowired
    public PlaceIntentService(
            ActivePetQueryService activePetQueryService,
            ChatQueryService chatQueryService,
            ChatMessageRepository messageRepository,
            ChatRoomParticipantRepository participantRepository,
            @Value("${app.meeting-card.ai.base-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${app.meeting-card.ai.timeout:5s}") Duration timeout
    ) {
        this.activePetQueryService = activePetQueryService;
        this.chatQueryService = chatQueryService;
        this.messageRepository = messageRepository;
        this.participantRepository = participantRepository;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.aiClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    PlaceIntentService(
            ActivePetQueryService activePetQueryService,
            ChatQueryService chatQueryService,
            ChatMessageRepository messageRepository,
            ChatRoomParticipantRepository participantRepository,
            RestClient aiClient
    ) {
        this.activePetQueryService = activePetQueryService;
        this.chatQueryService = chatQueryService;
        this.messageRepository = messageRepository;
        this.participantRepository = participantRepository;
        this.aiClient = aiClient;
    }

    public PlaceIntentResponse decide(long userId, long roomId, long triggerMessageId) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        chatQueryService.requireParticipant(roomId, actor.petId());
        ChatMessage trigger = messageRepository.findById(triggerMessageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (!trigger.getRoom().getId().equals(roomId)
                || trigger.getSenderType() != SenderType.PET
                || trigger.getType() != MessageType.TEXT
                || !actor.petId().equals(trigger.getSenderPetId())
                || !containsPlaceKeyword(trigger.getBody())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        List<Long> participantPetIds = participantRepository.findByRoomId(roomId).stream()
                .filter(participant -> participant.getLeftAt() == null)
                .map(ChatRoomParticipant::getPetId)
                .distinct()
                .toList();
        if (participantPetIds.isEmpty() || !participantPetIds.contains(actor.petId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        List<ChatMessage> newestFirst = messageRepository.findContextUpTo(
                roomId, triggerMessageId, SenderType.PET, MessageType.TEXT,
                PageRequest.of(0, MAX_CONTEXT_MESSAGES));
        List<ChatMessage> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);
        AiPlaceIntentResponse response = aiClient.post()
                .uri("/api/v1/place-intent/decide")
                .body(new AiPlaceIntentRequest(
                        "room-" + roomId,
                        participantPetIds.stream().map(String::valueOf).toList(),
                        String.valueOf(actor.petId()),
                        chronological.stream().map(this::toMessage).toList()))
                .retrieve()
                .body(AiPlaceIntentResponse.class);
        return normalize(response, actor.petId());
    }

    private boolean containsPlaceKeyword(String body) {
        if (body == null) {
            return false;
        }
        return List.of(
                "동물병원", "동물약국", "문예회관", "미술관", "미용", "박물관",
                "반려동물용품", "식당", "여행지", "위탁관리", "카페", "펜션", "호텔"
        ).stream().anyMatch(body::contains);
    }

    private AiMessage toMessage(ChatMessage message) {
        return new AiMessage(
                String.valueOf(message.getSenderPetId()),
                message.getBody(),
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                        message.getCreatedAt().atZone(SEOUL)));
    }

    private PlaceIntentResponse normalize(AiPlaceIntentResponse response, long actorPetId) {
        if (response == null || !String.valueOf(actorPetId).equals(response.targetUserId())) {
            return suppressed(actorPetId);
        }
        try {
            PlaceIntentResponse.Decision decision = PlaceIntentResponse.Decision.valueOf(
                    response.decision().trim().toUpperCase(Locale.ROOT));
            PlaceIntentResponse.PlaceType type = response.placeType() == null
                    ? null
                    : PlaceIntentResponse.PlaceType.valueOf(
                            response.placeType().trim().toUpperCase(Locale.ROOT));
            if (decision != PlaceIntentResponse.Decision.SHOW || type == null) {
                return suppressed(actorPetId);
            }
            return new PlaceIntentResponse(decision, type, actorPetId);
        } catch (RuntimeException ignored) {
            return suppressed(actorPetId);
        }
    }

    private PlaceIntentResponse suppressed(long actorPetId) {
        return new PlaceIntentResponse(
                PlaceIntentResponse.Decision.SUPPRESS, null, actorPetId);
    }

    record AiPlaceIntentRequest(
            @JsonProperty("room_id") String roomId,
            List<String> participants,
            @JsonProperty("trigger_sender_id") String triggerSenderId,
            List<AiMessage> messages
    ) {
    }

    record AiMessage(
            @JsonProperty("sender_id") String senderId,
            String content,
            @JsonProperty("sent_at") String sentAt
    ) {
    }

    record AiPlaceIntentResponse(
            String decision,
            @JsonProperty("place_type") String placeType,
            @JsonProperty("target_user_id") String targetUserId
    ) {
    }
}
