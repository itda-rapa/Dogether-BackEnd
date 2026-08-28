package itda.meetingcard.service;

import itda.chat.domain.ChatRoom;
import itda.chat.domain.ChatRoomParticipant;
import itda.chat.domain.RoomStatus;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.meetingcard.domain.CardDraft;
import itda.meetingcard.dto.OpenChatPlaceDraftRequest;
import itda.meetingcard.dto.response.OpenChatCardDraftResponse;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** AI를 호출하지 않고 지도 시설과 현재 방 참여자 스냅샷으로 약속 초안을 만든다. */
@Service
@RequiredArgsConstructor
public class OpenChatPlaceDraftService {

    private final ActivePetQueryService activePetQueryService;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomParticipantRepository participantRepository;
    private final CardDraftTransactionService transactionService;

    @Transactional
    public OpenChatCardDraftResponse create(
            long userId,
            long roomId,
            OpenChatPlaceDraftRequest request
    ) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        if (!room.isOpenChat() || room.getStatus() != RoomStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }
        if (!participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(roomId, actor.petId())) {
            throw new BusinessException(ErrorCode.NOT_PARTICIPANT_OF_CHAT_ROOM);
        }

        List<Long> participantPetIds = participantRepository.findByRoomId(roomId).stream()
                .filter(participant -> participant.getLeftAt() == null)
                .map(ChatRoomParticipant::getPetId)
                .distinct()
                .toList();
        if (participantPetIds.size() < 2) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        CardDraft draft = new CardDraft(
                roomId,
                actor.petId(),
                request.cardType(),
                request.placeText().trim(),
                null,
                null,
                null,
                null
        );
        return transactionService.saveOpenChatDraft(draft, participantPetIds);
    }
}
