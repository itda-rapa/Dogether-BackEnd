package itda.friend.service;

import itda.chat.domain.RoomOrigin;
import itda.chat.dto.EnsureDirectRoomResult;
import itda.chat.service.ChatRoomService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRequest;
import itda.friend.domain.Friendship;
import itda.friend.dto.response.FriendRequestResponse;
import itda.friend.repository.FriendshipRepository;
import itda.friend.repository.FriendshipRepository.FriendshipCountRow;
import itda.friend.service.FriendRequestResponseAssembler.Snapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FriendRequestAcceptanceService {

    private static final int FRIEND_LIMIT = 50;

    private final FriendshipRepository friendshipRepository;
    private final ChatRoomService chatRoomService;
    private final FriendRequestResponseAssembler responseAssembler;

    public FriendRequestAcceptanceService(
            FriendshipRepository friendshipRepository,
            ChatRoomService chatRoomService,
            FriendRequestResponseAssembler responseAssembler
    ) {
        this.friendshipRepository = friendshipRepository;
        this.chatRoomService = chatRoomService;
        this.responseAssembler = responseAssembler;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public FriendRequestResponse accept(
            FriendRequest pending,
            Long actorPetId,
            Instant now
    ) {
        Long requesterPetId = pending.getRequesterPetId();
        Long targetPetId = pending.getTargetPetId();
        validateFriendLimit(requesterPetId, targetPetId);

        pending.accept(now);
        friendshipRepository.save(
                Friendship.create(requesterPetId, targetPetId)
        );
        friendshipRepository.flush();

        Snapshot snapshot = Snapshot.from(pending);
        EnsureDirectRoomResult room = chatRoomService.ensureDirectRoom(
                requesterPetId,
                targetPetId,
                RoomOrigin.FRIEND
        );
        return responseAssembler.accepted(
                snapshot,
                actorPetId,
                room.roomId()
        );
    }

    private void validateFriendLimit(Long firstPetId, Long secondPetId) {
        Map<Long, Long> counts = friendshipRepository
                .countRelationshipsByPetIds(List.of(firstPetId, secondPetId))
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        FriendshipCountRow::getPetId,
                        FriendshipCountRow::getFriendCount
                ));
        if (counts.getOrDefault(firstPetId, 0L) >= FRIEND_LIMIT
                || counts.getOrDefault(secondPetId, 0L) >= FRIEND_LIMIT) {
            throw new BusinessException(ErrorCode.FRIEND_LIMIT_EXCEEDED);
        }
    }
}
