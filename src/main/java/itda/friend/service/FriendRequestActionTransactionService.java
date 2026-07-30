package itda.friend.service;

import itda.block.service.BlockRelationshipQueryService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRequest;
import itda.friend.domain.FriendRequestStatus;
import itda.friend.dto.response.FriendRequestResponse;
import itda.friend.repository.FriendRequestRepository;
import itda.friend.repository.FriendRequestRepository.FriendRequestPairRow;
import itda.friend.repository.FriendshipRepository;
import itda.friend.service.FriendRequestActionResult.Accepted;
import itda.friend.service.FriendRequestActionResult.Rejected;
import itda.friend.service.FriendRequestActionResult.Terminal;
import itda.friend.service.FriendRequestResponseAssembler.Snapshot;
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.dto.LockedPetContext;
import itda.interaction.dto.LockedUserContext;
import itda.interaction.service.InteractionPairLockService;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.user.domain.AccountStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FriendRequestActionTransactionService {

    private final ActivePetQueryService activePetQueryService;
    private final FriendRequestRepository friendRequestRepository;
    private final InteractionPairLockService interactionPairLockService;
    private final BlockRelationshipQueryService blockRelationshipQueryService;
    private final FriendshipRepository friendshipRepository;
    private final FriendRequestAcceptanceService acceptanceService;
    private final FriendRequestResponseAssembler responseAssembler;
    private final Clock clock;

    @Autowired
    public FriendRequestActionTransactionService(
            ActivePetQueryService activePetQueryService,
            FriendRequestRepository friendRequestRepository,
            InteractionPairLockService interactionPairLockService,
            BlockRelationshipQueryService blockRelationshipQueryService,
            FriendshipRepository friendshipRepository,
            FriendRequestAcceptanceService acceptanceService,
            FriendRequestResponseAssembler responseAssembler
    ) {
        this(
                activePetQueryService,
                friendRequestRepository,
                interactionPairLockService,
                blockRelationshipQueryService,
                friendshipRepository,
                acceptanceService,
                responseAssembler,
                Clock.systemUTC()
        );
    }

    FriendRequestActionTransactionService(
            ActivePetQueryService activePetQueryService,
            FriendRequestRepository friendRequestRepository,
            InteractionPairLockService interactionPairLockService,
            BlockRelationshipQueryService blockRelationshipQueryService,
            FriendshipRepository friendshipRepository,
            FriendRequestAcceptanceService acceptanceService,
            FriendRequestResponseAssembler responseAssembler,
            Clock clock
    ) {
        this.activePetQueryService = activePetQueryService;
        this.friendRequestRepository = friendRequestRepository;
        this.interactionPairLockService = interactionPairLockService;
        this.blockRelationshipQueryService = blockRelationshipQueryService;
        this.friendshipRepository = friendshipRepository;
        this.acceptanceService = acceptanceService;
        this.responseAssembler = responseAssembler;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FriendRequestActionResult accept(
            Long authenticatedUserId,
            Long requestId
    ) {
        PreparedAction prepared = prepare(
                authenticatedUserId,
                requestId,
                ActorRole.TARGET
        );
        validateCounterpartForAcceptance(prepared.pair());
        validateDifferentOwner(prepared.pair());
        validateNotBlocked(prepared.pair());
        validatePending(prepared.request());

        Instant now = clock.instant();
        if (expireIfNecessary(prepared.request(), now)) {
            return Terminal.EXPIRED;
        }

        Long requesterPetId = prepared.request().getRequesterPetId();
        Long targetPetId = prepared.request().getTargetPetId();
        long petLowId = Math.min(requesterPetId, targetPetId);
        long petHighId = Math.max(requesterPetId, targetPetId);
        if (friendshipRepository.existsByPetLowIdAndPetHighId(
                petLowId,
                petHighId
        )) {
            throw new BusinessException(ErrorCode.FRIENDSHIP_ALREADY_EXISTS);
        }

        FriendRequestResponse response = acceptanceService.accept(
                prepared.request(),
                prepared.activePet().petId(),
                now
        );
        return new Accepted(response);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FriendRequestActionResult reject(
            Long authenticatedUserId,
            Long requestId
    ) {
        PreparedAction prepared = prepare(
                authenticatedUserId,
                requestId,
                ActorRole.TARGET
        );
        validatePending(prepared.request());

        Instant now = clock.instant();
        if (expireIfNecessary(prepared.request(), now)) {
            return Terminal.EXPIRED;
        }

        prepared.request().reject(now);
        friendRequestRepository.flush();
        FriendRequestResponse response = responseAssembler.rejected(
                Snapshot.from(prepared.request())
        );
        return new Rejected(response);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FriendRequestActionResult cancel(
            Long authenticatedUserId,
            Long requestId
    ) {
        PreparedAction prepared = prepare(
                authenticatedUserId,
                requestId,
                ActorRole.REQUESTER
        );
        validatePending(prepared.request());

        Instant now = clock.instant();
        if (expireIfNecessary(prepared.request(), now)) {
            return Terminal.EXPIRED;
        }

        prepared.request().cancel(now);
        friendRequestRepository.flush();
        return Terminal.CANCELED;
    }

    private PreparedAction prepare(
            Long authenticatedUserId,
            Long requestId,
            ActorRole actorRole
    ) {
        validateRequired(authenticatedUserId, requestId);
        ActivePetContext activePet =
                activePetQueryService.requireActivePet(authenticatedUserId);
        FriendRequestPairRow projected = friendRequestRepository
                .findPairById(requestId)
                .orElseThrow(FriendRequestActionTransactionService::notFound);
        validateCandidateRole(activePet, projected, actorRole);

        InteractionPairContext pair =
                interactionPairLockService.lockInteractionPair(
                        projected.getRequesterPetId(),
                        projected.getTargetPetId()
                );
        FriendRequest request = friendRequestRepository
                .findByIdForUpdate(requestId)
                .orElseThrow(
                        FriendRequestActionTransactionService::
                                concurrentUpdateConflict
                );
        validateLockedStructure(requestId, projected, pair, request);
        validateLockedRole(activePet, request, actorRole);
        validateActor(
                authenticatedUserId,
                activePet,
                pair,
                actorRole
        );
        return new PreparedAction(activePet, pair, request);
    }

    private void validateRequired(Long authenticatedUserId, Long requestId) {
        if (authenticatedUserId == null || requestId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private void validateCandidateRole(
            ActivePetContext activePet,
            FriendRequestPairRow projected,
            ActorRole actorRole
    ) {
        Long expectedActorPetId = actorRole.petId(projected);
        if (!Objects.equals(activePet.petId(), expectedActorPetId)) {
            throw notFound();
        }
    }

    private void validateLockedStructure(
            Long requestId,
            FriendRequestPairRow projected,
            InteractionPairContext pair,
            FriendRequest request
    ) {
        if (!Objects.equals(projected.getRequestId(), requestId)
                || !Objects.equals(request.getId(), requestId)
                || !Objects.equals(
                projected.getRequesterPetId(),
                request.getRequesterPetId()
        )
                || !Objects.equals(
                projected.getTargetPetId(),
                request.getTargetPetId()
        )
                || !Objects.equals(
                pair.sourcePet().petId(),
                request.getRequesterPetId()
        )
                || !Objects.equals(
                pair.targetPet().petId(),
                request.getTargetPetId()
        )
                || !Objects.equals(
                pair.sourcePet().ownerUserId(),
                pair.sourceUser().userId()
        )
                || !Objects.equals(
                pair.targetPet().ownerUserId(),
                pair.targetUser().userId()
        )) {
            throw concurrentUpdateConflict();
        }
    }

    private void validateLockedRole(
            ActivePetContext activePet,
            FriendRequest request,
            ActorRole actorRole
    ) {
        Long expectedActorPetId = actorRole.petId(request);
        if (!Objects.equals(activePet.petId(), expectedActorPetId)) {
            throw notFound();
        }
    }

    private void validateActor(
            Long authenticatedUserId,
            ActivePetContext activePet,
            InteractionPairContext pair,
            ActorRole actorRole
    ) {
        LockedUserContext actorUser = actorRole.user(pair);
        LockedPetContext actorPet = actorRole.pet(pair);
        if (!Objects.equals(authenticatedUserId, actorUser.userId())
                || !Objects.equals(
                activePet.ownerUserId(),
                actorUser.userId()
        )
                || !Objects.equals(activePet.petId(), actorPet.petId())
                || !Objects.equals(
                actorPet.ownerUserId(),
                actorUser.userId()
        )) {
            throw concurrentUpdateConflict();
        }
        if (actorUser.accountStatus() != AccountStatus.ACTIVE
                || !Objects.equals(
                actorUser.activePetId(),
                actorPet.petId()
        )
                || actorPet.status() != PetStatus.ACTIVE
                || actorPet.deletedAt() != null) {
            throw new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED);
        }
    }

    private void validateCounterpartForAcceptance(
            InteractionPairContext pair
    ) {
        LockedUserContext counterpartUser = pair.sourceUser();
        LockedPetContext counterpartPet = pair.sourcePet();
        if (counterpartPet.status() == PetStatus.DELETED
                || counterpartPet.deletedAt() != null) {
            throw new BusinessException(ErrorCode.PET_NOT_FOUND);
        }
        if (counterpartPet.status() != PetStatus.ACTIVE
                || counterpartUser.accountStatus() != AccountStatus.ACTIVE) {
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

    private void validatePending(FriendRequest request) {
        if (request.getStatus() != FriendRequestStatus.PENDING) {
            throw new BusinessException(ErrorCode.FRIEND_REQUEST_NOT_PENDING);
        }
    }

    private boolean expireIfNecessary(
            FriendRequest request,
            Instant now
    ) {
        if (!request.isExpiredAt(now)) {
            return false;
        }
        request.expire();
        friendRequestRepository.flush();
        return true;
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.FRIEND_REQUEST_NOT_FOUND);
    }

    private static BusinessException concurrentUpdateConflict() {
        return new BusinessException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
    }

    private enum ActorRole {
        REQUESTER {
            @Override
            Long petId(FriendRequestPairRow row) {
                return row.getRequesterPetId();
            }

            @Override
            Long petId(FriendRequest request) {
                return request.getRequesterPetId();
            }

            @Override
            LockedUserContext user(InteractionPairContext pair) {
                return pair.sourceUser();
            }

            @Override
            LockedPetContext pet(InteractionPairContext pair) {
                return pair.sourcePet();
            }
        },
        TARGET {
            @Override
            Long petId(FriendRequestPairRow row) {
                return row.getTargetPetId();
            }

            @Override
            Long petId(FriendRequest request) {
                return request.getTargetPetId();
            }

            @Override
            LockedUserContext user(InteractionPairContext pair) {
                return pair.targetUser();
            }

            @Override
            LockedPetContext pet(InteractionPairContext pair) {
                return pair.targetPet();
            }
        };

        abstract Long petId(FriendRequestPairRow row);

        abstract Long petId(FriendRequest request);

        abstract LockedUserContext user(InteractionPairContext pair);

        abstract LockedPetContext pet(InteractionPairContext pair);
    }

    private record PreparedAction(
            ActivePetContext activePet,
            InteractionPairContext pair,
            FriendRequest request
    ) {
    }
}
