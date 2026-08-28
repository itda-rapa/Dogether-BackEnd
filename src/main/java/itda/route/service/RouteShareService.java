package itda.route.service;

import itda.chat.domain.ChatMessage;
import itda.chat.dto.response.ChatMessageResponse;
import itda.chat.event.ChatMessageCommittedEvent;
import itda.chat.repository.ChatMessageRepository;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.chat.service.ChatMessageResponseAssembler;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.route.dto.RouteShareRequest;
import itda.route.dto.RouteResponse;
import java.util.UUID;
import itda.route.repository.RouteRequestRepository;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RouteShareService {

    private final JdbcTemplate jdbcTemplate;
    private final ChatMessageRepository messageRepository;
    private final ChatRoomRepository roomRepository;
    private final ChatRoomParticipantRepository participantRepository;
    private final ActivePetQueryService activePetQueryService;
    private final RouteRequestRepository routeRepository;
    private final RouteEnvironmentService environmentService;
    private final ChatMessageResponseAssembler responseAssembler;
    private final ApplicationEventPublisher eventPublisher;

    public RouteShareService(JdbcTemplate jdbcTemplate, ChatMessageRepository messageRepository,
                             ChatRoomRepository roomRepository,
                             ChatRoomParticipantRepository participantRepository,
                              ActivePetQueryService activePetQueryService,
                              RouteRequestRepository routeRepository,
                              RouteEnvironmentService environmentService,
                              ChatMessageResponseAssembler responseAssembler,
                             ApplicationEventPublisher eventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.messageRepository = messageRepository;
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.activePetQueryService = activePetQueryService;
        this.routeRepository = routeRepository;
        this.environmentService = environmentService;
        this.responseAssembler = responseAssembler;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ChatMessageResponse share(long userId, long roomId, RouteShareRequest request) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        var room = roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        if (!room.isOpenChat()
                || !participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(roomId, actor.petId())) {
            throw new BusinessException(ErrorCode.CHAT_SENDER_NOT_PARTICIPANT);
        }
        if (!routeRepository.isCompletedAndOwned(request.routeId(), userId)) {
            throw new BusinessException(ErrorCode.ROUTE_SHARE_FORBIDDEN);
        }

        Map<String, Object> saved = jdbcTemplate.queryForMap("""
                INSERT INTO chat_messages (
                    room_id, sender_type, sender_pet_id, type, shared_route_id, client_message_id
                ) VALUES (?, 'PET', ?, 'ROUTE_SHARE', ?, ?)
                ON CONFLICT (room_id, client_message_id)
                DO UPDATE SET client_message_id = chat_messages.client_message_id
                RETURNING id, (xmax = 0) AS created
                """, roomId, actor.petId(), request.routeId(), request.clientMessageId());
        long messageId = ((Number) saved.get("id")).longValue();
        ChatMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        if (!Objects.equals(message.getSharedRouteId(), request.routeId())
                || !Objects.equals(message.getSenderPetId(), actor.petId())) {
            throw new BusinessException(ErrorCode.CHAT_DUPLICATE_MESSAGE);
        }
        ChatMessageResponse response = responseAssembler.toResponse(message, actor.nickname());
        if (Boolean.TRUE.equals(saved.get("created"))) {
            roomRepository.activateAndTouchLastMessageAt(roomId);
            eventPublisher.publishEvent(new ChatMessageCommittedEvent(room.getType(), response));
        }
        return response;
    }

    @Transactional
    public RouteResponse getShared(long userId, long roomId, UUID routeId) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        var room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        if (!room.isOpenChat()
                || !participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(
                        roomId, actor.petId())) {
            throw new BusinessException(ErrorCode.CHAT_SENDER_NOT_PARTICIPANT);
        }
        RouteResponse response = routeRepository.findSharedInRoom(routeId, roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_NOT_FOUND));
        if ("COMPLETED".equals(response.status()) && (
                response.environmentInfo() == null
                || !"AVAILABLE".equals(response.environmentInfo().path("status").asText()))) {
            routeRepository.updateEnvironment(routeId, environmentService.load(response));
            return routeRepository.findSharedInRoom(routeId, roomId).orElse(response);
        }
        return response;
    }
}
