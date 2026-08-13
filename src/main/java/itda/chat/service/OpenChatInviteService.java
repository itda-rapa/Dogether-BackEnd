package itda.chat.service;

import itda.chat.domain.ChatRoom;
import itda.chat.domain.ChatRoomParticipant;
import itda.chat.domain.RoomStatus;
import itda.chat.dto.response.OpenChatInviteResponse;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.repository.FriendshipRepository;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class OpenChatInviteService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomParticipantRepository participantRepository;
    private final FriendshipRepository friendshipRepository;
    private final ActivePetQueryService activePetQueryService;
    private final ChatAuthorizationCacheService chatAuthorizationCacheService;

    @Transactional
    public OpenChatInviteResponse invite(long userId, long roomId, long targetPetId) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        ChatRoom room = findActiveOpenChatRoomForUpdate(roomId);

        if (!participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(
                roomId, actor.petId())) {
            throw new BusinessException(ErrorCode.NOT_PARTICIPANT_OF_CHAT_ROOM);
        }

        long petLowId = Math.min(actor.petId(), targetPetId);
        long petHighId = Math.max(actor.petId(), targetPetId);
        if (petLowId == petHighId
                || !friendshipRepository.existsByPetLowIdAndPetHighId(petLowId, petHighId)) {
            throw new BusinessException(ErrorCode.FRIENDSHIP_NOT_FOUND);
        }

        Optional<ChatRoomParticipant> existing =
                participantRepository.findByRoomIdAndPetId(roomId, targetPetId);
        if (existing.isPresent() && existing.get().getLeftAt() == null) {
            long activeParticipants = participantRepository.countByRoomIdAndLeftAtIsNull(roomId);
            cacheParticipantAfterCommit(roomId, targetPetId);
            return new OpenChatInviteResponse(roomId, targetPetId, false, activeParticipants);
        }

        long activeParticipants = participantRepository.countByRoomIdAndLeftAtIsNull(roomId);
        if (activeParticipants >= room.getMaxParticipants()) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_FULL);
        }

        if (existing.isPresent()) {
            existing.get().rejoin();
        } else {
            participantRepository.save(ChatRoomParticipant.join(room, targetPetId));
        }
        cacheParticipantAfterCommit(roomId, targetPetId);
        return new OpenChatInviteResponse(roomId, targetPetId, true, activeParticipants + 1);
    }

    private ChatRoom findActiveOpenChatRoomForUpdate(long roomId) {
        ChatRoom room = chatRoomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        if (!room.isOpenChat() || room.getStatus() != RoomStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }
        return room;
    }

    private void cacheParticipantAfterCommit(long roomId, long petId) {
        Runnable action = () -> chatAuthorizationCacheService.addParticipant(roomId, petId);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
