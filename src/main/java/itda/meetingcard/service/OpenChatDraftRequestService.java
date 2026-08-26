package itda.meetingcard.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import itda.chat.domain.ChatMessage;
import itda.chat.domain.ChatRoom;
import itda.chat.domain.ChatRoomParticipant;
import itda.chat.domain.MessageType;
import itda.chat.domain.RoomStatus;
import itda.chat.domain.SenderType;
import itda.chat.repository.ChatMessageRepository;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.meetingcard.ai.MeetingDraftAiProperties;
import itda.meetingcard.domain.CardDraft;
import itda.meetingcard.domain.CardDraftFallbackReason;
import itda.meetingcard.domain.MeetingCardType;
import itda.meetingcard.dto.response.OpenChatCardDraftResponse;
import itda.meetingcard.repository.CardDraftParticipantRepository;
import itda.meetingcard.repository.CardDraftRepository;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** 오픈채팅 대화를 AI v2에 보내고, 선택 가능한 영속 초안 목록을 반환한다. */
@Service
public class OpenChatDraftRequestService {

    private static final Logger log = LoggerFactory.getLogger(OpenChatDraftRequestService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MAX_SOURCE_MESSAGES = 30;
    private static final int MIN_SOURCE_MESSAGES = 2;
    private static final Duration SOURCE_WINDOW = Duration.ofHours(24);
    private static final int MAX_PLACE_TEXT = 500;

    private final ActivePetQueryService activePetQueryService;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomParticipantRepository participantRepository;
    private final ChatMessageRepository messageRepository;
    private final CardDraftTransactionService transactionService;
    private final CardDraftRepository cardDraftRepository;
    private final CardDraftParticipantRepository cardDraftParticipantRepository;
    private final RestClient aiClient;
    private final Clock clock;

    @Autowired
    public OpenChatDraftRequestService(
            ActivePetQueryService activePetQueryService,
            ChatRoomRepository chatRoomRepository,
            ChatRoomParticipantRepository participantRepository,
            ChatMessageRepository messageRepository,
            CardDraftTransactionService transactionService,
            CardDraftRepository cardDraftRepository,
            CardDraftParticipantRepository cardDraftParticipantRepository,
            MeetingDraftAiProperties aiProperties
    ) {
        this(activePetQueryService, chatRoomRepository, participantRepository, messageRepository,
                transactionService, cardDraftRepository, cardDraftParticipantRepository,
                buildClient(aiProperties.baseUrl(), aiProperties.timeout()), Clock.systemUTC());
    }

    OpenChatDraftRequestService(
            ActivePetQueryService activePetQueryService,
            ChatRoomRepository chatRoomRepository,
            ChatRoomParticipantRepository participantRepository,
            ChatMessageRepository messageRepository,
            CardDraftTransactionService transactionService,
            RestClient aiClient,
            Clock clock
    ) {
        this(activePetQueryService, chatRoomRepository, participantRepository, messageRepository,
                transactionService, null, null, aiClient, clock);
    }

    OpenChatDraftRequestService(
            ActivePetQueryService activePetQueryService,
            ChatRoomRepository chatRoomRepository,
            ChatRoomParticipantRepository participantRepository,
            ChatMessageRepository messageRepository,
            CardDraftTransactionService transactionService,
            CardDraftRepository cardDraftRepository,
            CardDraftParticipantRepository cardDraftParticipantRepository,
            RestClient aiClient,
            Clock clock
    ) {
        this.activePetQueryService = activePetQueryService;
        this.chatRoomRepository = chatRoomRepository;
        this.participantRepository = participantRepository;
        this.messageRepository = messageRepository;
        this.transactionService = transactionService;
        this.cardDraftRepository = cardDraftRepository;
        this.cardDraftParticipantRepository = cardDraftParticipantRepository;
        this.aiClient = aiClient;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OpenChatCardDraftResponse getDraft(long userId, long roomId, long draftId) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        if (!room.isOpenChat()
                || !participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(
                        roomId, actor.petId())) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }
        CardDraft draft = cardDraftRepository.findById(draftId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (!draft.getRoomId().equals(roomId)
                || !draft.getRequestedByPetId().equals(actor.petId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        List<Long> participantPetIds = cardDraftParticipantRepository
                .findByCardDraftIdOrderByIdAsc(draftId).stream()
                .map(participant -> participant.getPetId())
                .toList();
        return transactionService.toOpenChatResponse(draft, participantPetIds);
    }

    private static RestClient buildClient(String baseUrl, Duration timeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Transactional(propagation = Propagation.NEVER)
    public List<OpenChatCardDraftResponse> createDrafts(long userId, long roomId) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        if (!room.isOpenChat() || room.getStatus() != RoomStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }
        if (!participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(roomId, actor.petId())) {
            throw new BusinessException(ErrorCode.NOT_PARTICIPANT_OF_CHAT_ROOM);
        }

        List<Long> roomPetIds = participantRepository.findByRoomId(roomId).stream()
                .filter(participant -> participant.getLeftAt() == null)
                .map(ChatRoomParticipant::getPetId)
                .distinct()
                .toList();
        if (roomPetIds.size() < 3) {
            throw new BusinessException(ErrorCode.OPEN_CHAT_AI_REQUIRES_THREE_PARTICIPANTS);
        }

        List<ChatMessage> newestFirst = messageRepository.findRecentMessagesForDraft(
                roomId, SenderType.PET, MessageType.TEXT, clock.instant().minus(SOURCE_WINDOW),
                PageRequest.of(0, MAX_SOURCE_MESSAGES));
        if (newestFirst.size() < MIN_SOURCE_MESSAGES) {
            return List.of(saveFallback(roomId, actor.petId(), roomPetIds,
                    CardDraftFallbackReason.INSUFFICIENT_CONTEXT));
        }

        List<AiDraftV2Response> extracted;
        try {
            extracted = aiClient.post().uri("/api/v2/meeting-drafts/extract")
                    .body(toRequest(roomId, roomPetIds, newestFirst))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception exception) {
            log.warn("Open-chat AI draft extraction failed for room {}", roomId, exception);
            return List.of(saveFallback(roomId, actor.petId(), roomPetIds,
                    fallbackReason(exception)));
        }

        if (extracted == null || extracted.isEmpty()) {
            return List.of(saveDraft(roomId, actor.petId(), roomPetIds, null, null, null, null));
        }
        List<OpenChatCardDraftResponse> drafts = extracted.stream()
                .filter(Objects::nonNull)
                .map(result -> new NormalizedDraft(result,
                        normalizeParticipants(result.participantIds(), roomPetIds, actor.petId())))
                .filter(result -> result.participants().size() >= 2)
                .map(result -> saveDraft(roomId, actor.petId(), result.participants(),
                        result.draft().meetingType(), result.draft().place(),
                        result.draft().date(), result.draft().time()))
                .toList();
        return drafts.isEmpty()
                ? List.of(saveDraft(roomId, actor.petId(), roomPetIds, null, null, null, null))
                : drafts;
    }

    private CardDraftFallbackReason fallbackReason(Exception exception) {
        if (exception instanceof RestClientResponseException response
                && response.getStatusCode().value() == 504) {
            return CardDraftFallbackReason.TIMEOUT;
        }
        if (exception instanceof ResourceAccessException
                && hasCause(exception, SocketTimeoutException.class)) {
            return CardDraftFallbackReason.TIMEOUT;
        }
        return CardDraftFallbackReason.MODEL_ERROR;
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }

    private AiDraftV2Request toRequest(long roomId, List<Long> roomPetIds,
                                       List<ChatMessage> newestFirst) {
        List<ChatMessage> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);
        List<AiMessageV2> messages = chronological.stream()
                .map(message -> new AiMessageV2(String.valueOf(message.getSenderPetId()),
                        message.getBody(), formatSentAt(message.getCreatedAt())))
                .toList();
        return new AiDraftV2Request(String.valueOf(roomId),
                LocalDate.ofInstant(clock.instant(), SEOUL).toString(),
                roomPetIds.stream().map(String::valueOf).toList(), messages);
    }

    static String formatSentAt(Instant sentAt) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(sentAt.atZone(SEOUL));
    }

    List<Long> normalizeParticipants(List<String> rawIds, List<Long> roomPetIds, long requesterPetId) {
        if (!roomPetIds.contains(requesterPetId)) return List.of();
        List<Long> normalized = new ArrayList<>();
        normalized.add(requesterPetId);
        if (rawIds != null) {
            rawIds.stream().map(this::parseLong).filter(roomPetIds::contains)
                    .filter(petId -> !normalized.contains(petId)).forEach(normalized::add);
        }
        return List.copyOf(normalized);
    }

    private Long parseLong(String value) {
        try { return Long.valueOf(value); } catch (RuntimeException ignored) { return Long.MIN_VALUE; }
    }

    private OpenChatCardDraftResponse saveFallback(long roomId, long requesterPetId,
                                                    List<Long> participants,
                                                    CardDraftFallbackReason reason) {
        return transactionService.saveOpenChatDraft(
                new CardDraft(roomId, requesterPetId, null, null, null, null, null, reason),
                participants);
    }

    private OpenChatCardDraftResponse saveDraft(long roomId, long requesterPetId,
                                                List<Long> participants, String type,
                                                String place, String date, String time) {
        MeetingCardType cardType = null;
        try { if (type != null) cardType = MeetingCardType.valueOf(type.trim().toUpperCase()); }
        catch (IllegalArgumentException ignored) { }
        String boundedPlace = place == null || place.length() <= MAX_PLACE_TEXT
                ? place : place.substring(0, MAX_PLACE_TEXT);
        Instant meetAt = null;
        String normalizedDate = normalizeDate(date);
        String normalizedTime = normalizeTime(time);
        try {
            if (normalizedDate != null && normalizedTime != null) {
                meetAt = LocalDate.parse(normalizedDate).atTime(LocalTime.parse(normalizedTime))
                        .atZone(SEOUL).toInstant();
            }
        } catch (RuntimeException ignored) { }
        return transactionService.saveOpenChatDraft(
                new CardDraft(roomId, requesterPetId, cardType, boundedPlace, meetAt,
                        normalizedDate, normalizedTime, null), participants);
    }

    private String normalizeDate(String date) {
        try { return date == null ? null : LocalDate.parse(date).toString(); }
        catch (RuntimeException ignored) { return null; }
    }

    private String normalizeTime(String time) {
        try {
            return time == null ? null : LocalTime.parse(time)
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        } catch (RuntimeException ignored) { return null; }
    }

    record AiDraftV2Request(
            @JsonProperty("room_id") String roomId,
            @JsonProperty("reference_date") String referenceDate,
            List<String> participants,
            List<AiMessageV2> messages) { }

    record AiMessageV2(
            @JsonProperty("sender_id") String senderId,
            String content,
            @JsonProperty("sent_at") String sentAt) { }

    record AiDraftV2Response(
            @JsonProperty("meeting_type") String meetingType,
            String date,
            String time,
            String place,
            @JsonProperty("participant_ids") List<String> participantIds) { }

    record NormalizedDraft(AiDraftV2Response draft, List<Long> participants) { }
}
