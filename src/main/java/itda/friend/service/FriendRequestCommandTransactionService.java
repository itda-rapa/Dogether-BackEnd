package itda.friend.service;

import itda.block.service.BlockRelationshipQueryService;
import itda.chat.domain.RoomOrigin;
import itda.chat.dto.EnsureDirectRoomResult;
import itda.chat.service.ChatRoomService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRelationship;
import itda.friend.domain.FriendRequest;
import itda.friend.domain.FriendRequestStatus;
import itda.friend.domain.Friendship;
import itda.friend.dto.response.FriendRequestPetResponse;
import itda.friend.dto.response.FriendRequestResponse;
import itda.friend.repository.FriendRequestRepository;
import itda.friend.repository.FriendshipRepository;
import itda.friend.repository.FriendshipRepository.FriendshipCountRow;
import itda.friend.service.FriendRequestCommandResult.Outcome;
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.dto.LockedPetContext;
import itda.interaction.dto.LockedUserContext;
import itda.interaction.service.InteractionPairLockService;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import itda.user.domain.AccountStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FriendRequestCommandTransactionService {

    private static final int FRIEND_LIMIT = 50;
    private static final Duration FRIEND_REQUEST_TTL = Duration.ofDays(7);

    private final ActivePetQueryService activePetQueryService;
    private final InteractionPairLockService interactionPairLockService;
    private final BlockRelationshipQueryService blockRelationshipQueryService;
    private final FriendRequestRepository friendRequestRepository;
    private final FriendshipRepository friendshipRepository;
    private final ChatRoomService chatRoomService;
    private final PetDisplayQueryService petDisplayQueryService;
    private final Clock clock;

    @Autowired
    public FriendRequestCommandTransactionService(
            ActivePetQueryService activePetQueryService,
            InteractionPairLockService interactionPairLockService,
            BlockRelationshipQueryService blockRelationshipQueryService,
            FriendRequestRepository friendRequestRepository,
            FriendshipRepository friendshipRepository,
            ChatRoomService chatRoomService,
            PetDisplayQueryService petDisplayQueryService
    ) {
        this(
                activePetQueryService,
                interactionPairLockService,
                blockRelationshipQueryService,
                friendRequestRepository,
                friendshipRepository,
                chatRoomService,
                petDisplayQueryService,
                Clock.systemUTC()
        );
    }

    FriendRequestCommandTransactionService(
            ActivePetQueryService activePetQueryService,
            InteractionPairLockService interactionPairLockService,
            BlockRelationshipQueryService blockRelationshipQueryService,
            FriendRequestRepository friendRequestRepository,
            FriendshipRepository friendshipRepository,
            ChatRoomService chatRoomService,
            PetDisplayQueryService petDisplayQueryService,
            Clock clock
    ) {
        this.activePetQueryService = activePetQueryService;
        this.interactionPairLockService = interactionPairLockService;
        this.blockRelationshipQueryService = blockRelationshipQueryService;
        this.friendRequestRepository = friendRequestRepository;
        this.friendshipRepository = friendshipRepository;
        this.chatRoomService = chatRoomService;
        this.petDisplayQueryService = petDisplayQueryService;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FriendRequestCommandResult execute(
            Long authenticatedUserId,
            Long targetPetId
    ) {
        validateRequired(authenticatedUserId, targetPetId);
        ActivePetContext activePet =
                activePetQueryService.requireActivePet(authenticatedUserId);
        InteractionPairContext pair =
                interactionPairLockService.lockInteractionPair(
                        activePet.petId(),
                        targetPetId
                );

        validateSource(authenticatedUserId, activePet, pair);
        validateTarget(pair);
        validateDifferentOwner(pair);
        validateNotBlocked(pair);

        long petLowId = Math.min(activePet.petId(), targetPetId);
        long petHighId = Math.max(activePet.petId(), targetPetId);
        if (friendshipRepository.existsByPetLowIdAndPetHighId(
                petLowId,
                petHighId
        )) {
            throw new BusinessException(
                    ErrorCode.FRIENDSHIP_ALREADY_EXISTS
            );
        }

        FriendRequest pending = friendRequestRepository
                .findPendingPairForUpdate(petLowId, petHighId)
                .orElse(null);
        Instant now = clock.instant();
        if (pending == null) {
            return createPending(activePet.petId(), targetPetId, now);
        }
        if (pending.isExpiredAt(now)) {
            pending.expire();
            friendRequestRepository.flush();
            return createPending(activePet.petId(), targetPetId, now);
        }
        if (pending.getRequesterPetId().equals(activePet.petId())) {
            throw new BusinessException(
                    ErrorCode.FRIEND_REQUEST_ALREADY_PENDING
            );
        }
        if (!pending.getRequesterPetId().equals(targetPetId)
                || !pending.getTargetPetId().equals(activePet.petId())) {
            throw new BusinessException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
        }
        return autoAccept(pending, activePet.petId(), targetPetId, now);
    }

    private FriendRequestCommandResult createPending(
            Long sourcePetId,
            Long targetPetId,
            Instant now
    ) {
        FriendRequest request = FriendRequest.createPending(
                sourcePetId,
                targetPetId,
                now,
                now.plus(FRIEND_REQUEST_TTL)
        );
        FriendRequest saved = friendRequestRepository.saveAndFlush(request);
        FriendRequestSnapshot snapshot = FriendRequestSnapshot.from(saved);
        FriendRequestResponse response = toResponse(
                snapshot,
                sourcePetId,
                null,
                false
        );
        return new FriendRequestCommandResult(response, Outcome.CREATED);
    }

    private FriendRequestCommandResult autoAccept(
            FriendRequest pending,
            Long sourcePetId,
            Long targetPetId,
            Instant now
    ) {
        validateFriendLimit(sourcePetId, targetPetId);
        pending.accept(now);
        friendshipRepository.save(
                Friendship.create(sourcePetId, targetPetId)
        );
        friendshipRepository.flush();

        FriendRequestSnapshot snapshot = FriendRequestSnapshot.from(pending);
        EnsureDirectRoomResult room = chatRoomService.ensureDirectRoom(
                sourcePetId,
                targetPetId,
                RoomOrigin.FRIEND
        );
        FriendRequestResponse response = toResponse(
                snapshot,
                sourcePetId,
                room.roomId(),
                true
        );
        return new FriendRequestCommandResult(
                response,
                Outcome.AUTO_ACCEPTED
        );
    }

    private void validateRequired(
            Long authenticatedUserId,
            Long targetPetId
    ) {
        if (authenticatedUserId == null || targetPetId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private void validateSource(
            Long authenticatedUserId,
            ActivePetContext activePet,
            InteractionPairContext pair
    ) {
        LockedUserContext sourceUser = pair.sourceUser();
        LockedPetContext sourcePet = pair.sourcePet();
        if (!Objects.equals(authenticatedUserId, sourceUser.userId())
                || !Objects.equals(
                activePet.ownerUserId(),
                sourceUser.userId()
        )
                || !Objects.equals(
                sourcePet.ownerUserId(),
                sourceUser.userId()
        )
                || !Objects.equals(activePet.petId(), sourcePet.petId())) {
            throw new BusinessException(
                    ErrorCode.CONCURRENT_UPDATE_CONFLICT
            );
        }
        if (sourceUser.accountStatus() != AccountStatus.ACTIVE
                || !Objects.equals(
                sourceUser.activePetId(),
                sourcePet.petId()
        )
                || sourcePet.status() != PetStatus.ACTIVE
                || sourcePet.deletedAt() != null) {
            throw new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED);
        }
    }

    private void validateTarget(InteractionPairContext pair) {
        LockedUserContext targetUser = pair.targetUser();
        LockedPetContext targetPet = pair.targetPet();
        if (!Objects.equals(
                targetPet.ownerUserId(),
                targetUser.userId()
        )) {
            throw new BusinessException(
                    ErrorCode.CONCURRENT_UPDATE_CONFLICT
            );
        }
        if (targetPet.status() == PetStatus.DELETED
                || targetPet.deletedAt() != null) {
            throw new BusinessException(ErrorCode.PET_NOT_FOUND);
        }
        if (targetPet.status() != PetStatus.ACTIVE
                || targetUser.accountStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.PET_NOT_ACTIVE);
        }
    }

    private void validateDifferentOwner(InteractionPairContext pair) {
        if (Objects.equals(
                pair.sourceUser().userId(),
                pair.targetUser().userId()
        )) {
            throw new BusinessException(
                    ErrorCode.SAME_OWNER_INTERACTION_FORBIDDEN
            );
        }
    }

    private void validateNotBlocked(InteractionPairContext pair) {
        if (blockRelationshipQueryService.existsBlockBetween(
                pair.sourceUser().userId(),
                pair.targetUser().userId()
        )) {
            throw new BusinessException(ErrorCode.BLOCKED_USER);
        }
    }

    private void validateFriendLimit(Long sourcePetId, Long targetPetId) {
        Map<Long, Long> counts = friendshipRepository
                .countRelationshipsByPetIds(List.of(sourcePetId, targetPetId))
                .stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        FriendshipCountRow::getPetId,
                        FriendshipCountRow::getFriendCount
                ));
        if (counts.getOrDefault(sourcePetId, 0L) >= FRIEND_LIMIT
                || counts.getOrDefault(targetPetId, 0L) >= FRIEND_LIMIT) {
            throw new BusinessException(ErrorCode.FRIEND_LIMIT_EXCEEDED);
        }
    }

    private FriendRequestResponse toResponse(
            FriendRequestSnapshot snapshot,
            Long sourcePetId,
            Long directRoomId,
            boolean accepted
    ) {
        Map<Long, PetDisplaySummary> pets =
                petDisplayQueryService.getPetDisplaySummaries(
                        List.of(
                                snapshot.requesterPetId(),
                                snapshot.targetPetId()
                        )
                );
        return new FriendRequestResponse(
                snapshot.requestId(),
                toPetResponse(
                        pets.get(snapshot.requesterPetId()),
                        snapshot.requesterPetId(),
                        sourcePetId,
                        accepted
                ),
                toPetResponse(
                        pets.get(snapshot.targetPetId()),
                        snapshot.targetPetId(),
                        sourcePetId,
                        accepted
                ),
                snapshot.status(),
                snapshot.requestedAt(),
                snapshot.respondedAt(),
                snapshot.expiresAt(),
                directRoomId
        );
    }

    private FriendRequestPetResponse toPetResponse(
            PetDisplaySummary pet,
            Long petId,
            Long sourcePetId,
            boolean accepted
    ) {
        FriendRelationship relationship;
        if (petId.equals(sourcePetId)) {
            relationship = FriendRelationship.NONE;
        } else if (accepted) {
            relationship = FriendRelationship.FRIEND;
        } else {
            relationship = FriendRelationship.REQUEST_SENT;
        }
        return FriendRequestPetResponse.from(pet, relationship);
    }

    private record FriendRequestSnapshot(
            Long requestId,
            Long requesterPetId,
            Long targetPetId,
            FriendRequestStatus status,
            Instant requestedAt,
            Instant respondedAt,
            Instant expiresAt
    ) {

        private static FriendRequestSnapshot from(FriendRequest request) {
            return new FriendRequestSnapshot(
                    request.getId(),
                    request.getRequesterPetId(),
                    request.getTargetPetId(),
                    request.getStatus(),
                    request.getRequestedAt(),
                    request.getRespondedAt(),
                    request.getExpiresAt()
            );
        }
    }
}
