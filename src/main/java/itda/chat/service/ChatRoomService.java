package itda.chat.service;

import itda.chat.domain.ChatRoom;
import itda.chat.domain.ChatRoomParticipant;
import itda.chat.domain.RoomOrigin;
import itda.chat.domain.RoomType;
import itda.chat.dto.EnsureDirectRoomResult;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomParticipantRepository participantRepository;

    /**
     * Ensure a DIRECT room exists for the given Pet pair.
     *
     * <p>Concurrency strategy:
     * <ol>
     *   <li>Fast-path lookup — return existing room immediately</li>
     *   <li>Atomic INSERT … ON CONFLICT DO NOTHING — exactly one writer wins</li>
     *   <li>Re-lookup after insert — retrieve the winning row</li>
     *   <li>Register participants only for the new room</li>
     * </ol>
     * The database partial unique index {@code uk_chat_room_direct_pair} is the source of truth.
     */
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
            try {
                participantRepository.save(ChatRoomParticipant.join(room, petAId));
                participantRepository.save(ChatRoomParticipant.join(room, petBId));
            } catch (DataIntegrityViolationException e) {
                // Another thread already registered participants — that is safe to ignore
            }
        }

        return new EnsureDirectRoomResult(room.getId(), isNew);
    }
}