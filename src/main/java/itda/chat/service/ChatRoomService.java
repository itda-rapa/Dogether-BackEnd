package itda.chat.service;

import itda.chat.domain.*;
import itda.chat.dto.EnsureDirectRoomResult;
import itda.chat.dto.request.OpenChatRoomCreateRequest;
import itda.chat.dto.request.OpenChatRoomUpdateRequest;
import itda.chat.dto.response.OpenChatRoomResponse;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;

@Service
@Transactional
@RequiredArgsConstructor
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomParticipantRepository participantRepository;
    private final ActivePetQueryService activePetQueryService;
    private final ChatAuthorizationCacheService chatAuthorizationCacheService;


    @Transactional
    public EnsureDirectRoomResult ensureDirectRoom(long petAId, long petBId, RoomOrigin origin) {
        if (petAId == petBId) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_SAME_PET_FORBIDDEN);
        }
        long lowId = Math.min(petAId, petBId);
        long highId = Math.max(petAId, petBId);

        // Fast path: room already exists
        var existing = chatRoomRepository.findByTypeAndPetLowIdAndPetHighId(RoomType.DIRECT, lowId, highId);
        if (existing.isPresent()) {
            return new EnsureDirectRoomResult(existing.get().getId(), false);
        }

        // Atomic insert — this is the concurrency gate
        int inserted = chatRoomRepository.insertDirectRoomOnConflict(
                RoomType.DIRECT.name(), itda.chat.domain.RoomStatus.ACTIVE.name(),
                origin.name(), lowId, highId);

        // Re-lookup after native insert (persistence context was cleared by clearAutomatically)
        ChatRoom room = chatRoomRepository.findByTypeAndPetLowIdAndPetHighId(RoomType.DIRECT, lowId, highId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        boolean isNew = (inserted > 0);

        // Register participants if this writer created the room
        if (isNew) {
            participantRepository.save(ChatRoomParticipant.join(room, petAId));
            participantRepository.save(ChatRoomParticipant.join(room, petBId));
            afterCommit(() -> chatAuthorizationCacheService.addParticipants(
                    room.getId(), List.of(petAId, petBId)));
        }

        return new EnsureDirectRoomResult(room.getId(), isNew);
    }

    public OpenChatRoomResponse createOpenChatRoom(
            long userId,
            OpenChatRoomCreateRequest request
    ) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);

        ChatRoom room = ChatRoom.openChat(
                normalizeTitle(request.title()),
                normalizeDescription(request.description()),
                actor.petId(),
                request.maxParticipants(),
                request.isPublic()
        );
        ChatRoom saved = chatRoomRepository.saveAndFlush(room);
        participantRepository.saveAndFlush(ChatRoomParticipant.join(saved, actor.petId()));
        cacheParticipantAfterCommit(userId, saved.getId(), actor.petId());

        return OpenChatRoomResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public Page<OpenChatRoomResponse> getOpenChatRooms(Pageable pageable) {
        return chatRoomRepository.findByTypeAndOriginAndStatusAndIsPublicTrue(
                        RoomType.GROUP,
                        RoomOrigin.OPEN_CHAT,
                        RoomStatus.ACTIVE,
                        pageable
                )
                .map(OpenChatRoomResponse::from);
    }

    @Transactional(readOnly = true)
    public OpenChatRoomResponse getOpenChatRoom(long userId, long roomId) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        ChatRoom room = findActiveOpenChatRoom(roomId);

        if (!room.getIsPublic()
                && !participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(roomId, actor.petId())) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }

        long activeParticipants = participantRepository.countByRoomIdAndLeftAtIsNull(roomId);
        return OpenChatRoomResponse.from(room, activeParticipants);
    }

    public OpenChatRoomResponse updateOpenChatRoom(
            long userId,
            long roomId,
            OpenChatRoomUpdateRequest request
    ) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        ChatRoom room = findActiveOpenChatRoom(roomId);
        requireOwner(room, actor.petId());

        long activeParticipants = participantRepository.countByRoomIdAndLeftAtIsNull(roomId);
        if (request.maxParticipants() < activeParticipants) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        room.updateOpenChat(
                normalizeTitle(request.title()),
                normalizeDescription(request.description()),
                request.maxParticipants(),
                request.isPublic()
        );
        chatRoomRepository.flush();

        return OpenChatRoomResponse.from(room);
    }

    public void deleteOpenChatRoom(long userId, long roomId) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        ChatRoom room = findActiveOpenChatRoom(roomId);
        requireOwner(room, actor.petId());

        room.archive(Instant.now());
        afterCommit(() -> chatAuthorizationCacheService.clearParticipants(roomId));
        chatRoomRepository.flush();
    }

    public OpenChatRoomResponse joinOpenChatRoom(long userId, long roomId) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        ChatRoom room = findActiveOpenChatRoomForUpdate(roomId);
        if (!room.getIsPublic() && !room.isOwnedBy(actor.petId())) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }

        var participant = participantRepository.findByRoomIdAndPetId(roomId, actor.petId());
        if (participant.isPresent()) {
            if (participant.get().getLeftAt() == null) {
                cacheParticipantAfterCommit(userId, roomId, actor.petId());
                return OpenChatRoomResponse.from(room);
            }
            long activeParticipants = participantRepository.countByRoomIdAndLeftAtIsNull(roomId);
            if (activeParticipants >= room.getMaxParticipants()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            participant.get().rejoin();
            cacheParticipantAfterCommit(userId, roomId, actor.petId());
            return OpenChatRoomResponse.from(room);
        }

        long activeParticipants = participantRepository.countByRoomIdAndLeftAtIsNull(roomId);
        if (activeParticipants >= room.getMaxParticipants()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        participantRepository.save(ChatRoomParticipant.join(room, actor.petId()));
        cacheParticipantAfterCommit(userId, roomId, actor.petId());
        return OpenChatRoomResponse.from(room);
    }

    public void leaveOpenChatRoom(long userId, long roomId) {
        ActivePetContext actor = activePetQueryService.requireActivePet(userId);
        ChatRoom room = findActiveOpenChatRoom(roomId);
        if (room.isOwnedBy(actor.petId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        ChatRoomParticipant participant = participantRepository.findByRoomIdAndPetId(roomId, actor.petId())
                .filter(existing -> existing.getLeftAt() == null)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_PARTICIPANT_OF_CHAT_ROOM));
        participant.leave(Instant.now());
        afterCommit(() -> chatAuthorizationCacheService.removeParticipant(roomId, actor.petId()));
    }

    private ChatRoom findActiveOpenChatRoom(long roomId) {
        ChatRoom room = chatRoomRepository.findByIdOrThrow(roomId);
        if (!room.isOpenChat() || room.getStatus() != RoomStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }
        return room;
    }

    private ChatRoom findActiveOpenChatRoomForUpdate(long roomId) {
        ChatRoom room = chatRoomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        if (!room.isOpenChat() || room.getStatus() != RoomStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }
        return room;
    }

    private void cacheParticipantAfterCommit(long userId, long roomId, long petId) {
        afterCommit(() -> {
            chatAuthorizationCacheService.cacheActivePet(userId, petId);
            chatAuthorizationCacheService.addParticipant(roomId, petId);
        });
    }

    private void afterCommit(Runnable action) {
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

    private void requireOwner(ChatRoom room, long petId) {
        if (!room.isOwnedBy(petId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private String normalizeTitle(String title) {
        return title == null ? null : title.trim();
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.trim();
    }

}
