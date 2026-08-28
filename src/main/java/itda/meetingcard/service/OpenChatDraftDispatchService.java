package itda.meetingcard.service;

import itda.chat.domain.ChatRoom;
import itda.chat.domain.ChatRoomParticipant;
import itda.chat.domain.RoomStatus;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.meetingcard.dto.event.OpenChatDraftRequestEvent;
import itda.meetingcard.dto.response.OpenChatDraftRequestResponse;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OpenChatDraftDispatchService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ActivePetQueryService activePetQueryService;
    private final ChatRoomRepository roomRepository;
    private final ChatRoomParticipantRepository participantRepository;
    private final OpenChatDraftRequestPublisher publisher;
    private final Clock clock;

    public OpenChatDraftRequestResponse request(long userId, long roomId) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        ChatRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        if (!room.isOpenChat() || room.getStatus() != RoomStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }
        List<Long> activePetIds = participantRepository.findByRoomId(roomId).stream()
                .filter(participant -> participant.getLeftAt() == null)
                .map(ChatRoomParticipant::getPetId)
                .distinct()
                .toList();
        if (!activePetIds.contains(actor.petId())) {
            throw new BusinessException(ErrorCode.NOT_PARTICIPANT_OF_CHAT_ROOM);
        }
        if (activePetIds.size() < 3) {
            throw new BusinessException(ErrorCode.OPEN_CHAT_AI_REQUIRES_THREE_PARTICIPANTS);
        }
        String requestId = UUID.randomUUID().toString();
        publisher.publish(new OpenChatDraftRequestEvent(
                requestId,
                roomId,
                userId,
                actor.petId(),
                LocalDate.ofInstant(clock.instant(), SEOUL)
        ));
        return new OpenChatDraftRequestResponse(requestId, roomId);
    }
}
