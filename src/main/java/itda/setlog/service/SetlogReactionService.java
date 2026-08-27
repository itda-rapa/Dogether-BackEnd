package itda.setlog.service;

import itda.block.service.BlockRelationshipQueryService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.service.InteractionPairLockService;
import itda.media.domain.MediaStatus;
import itda.notification.domain.NotificationTargetType;
import itda.notification.domain.NotificationType;
import itda.notification.service.NotificationCommandService;
import itda.pet.domain.Pet;
import itda.pet.domain.PetStatus;
import itda.pet.repository.PetRepository;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.setlog.domain.ReactionType;
import itda.setlog.domain.Setlog;
import itda.setlog.domain.SetlogReaction;
import itda.setlog.domain.SetlogStatus;
import itda.setlog.dto.SetlogReactionResponse;
import itda.setlog.repository.SetlogReactionRepository;
import itda.setlog.repository.SetlogRepository;
import itda.user.domain.AccountStatus;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SetlogReactionService {

    private static final List<MediaStatus> PLAYABLE_MEDIA_STATUSES =
            List.of(MediaStatus.UPLOADED, MediaStatus.COMPLETED);

    private final SetlogRepository setlogRepository;
    private final SetlogReactionRepository setlogReactionRepository;
    private final PetRepository petRepository;
    private final ActivePetQueryService activePetQueryService;
    private final InteractionPairLockService interactionPairLockService;
    private final BlockRelationshipQueryService blockRelationshipQueryService;
    private final NotificationCommandService notificationCommandService;

    public SetlogReactionService(
            SetlogRepository setlogRepository,
            SetlogReactionRepository setlogReactionRepository,
            PetRepository petRepository,
            ActivePetQueryService activePetQueryService,
            InteractionPairLockService interactionPairLockService,
            BlockRelationshipQueryService blockRelationshipQueryService,
            NotificationCommandService notificationCommandService
    ) {
        this.setlogRepository = setlogRepository;
        this.setlogReactionRepository = setlogReactionRepository;
        this.petRepository = petRepository;
        this.activePetQueryService = activePetQueryService;
        this.interactionPairLockService = interactionPairLockService;
        this.blockRelationshipQueryService = blockRelationshipQueryService;
        this.notificationCommandService = notificationCommandService;
    }

    /**
     * Adds a reaction idempotently. Locking the Setlog serializes counter
     * changes, while the database unique constraint protects the reaction row.
     */
    @Transactional
    public SetlogReactionResponse addReaction(
            Long userId,
            Long setlogId,
            ReactionType type
    ) {
        ReactionContext context = requireReactionContext(userId, setlogId);
        boolean exists = setlogReactionRepository
                .findBySetlog_IdAndReactorPet_IdAndType(
                        setlogId,
                        context.activePet().getId(),
                        type
                )
                .isPresent();
        if (!exists) {
            setlogReactionRepository.save(
                    SetlogReaction.create(
                            context.setlog(),
                            context.activePet(),
                            type
                    )
            );
            context.setlog().incrementReaction(type);
            notificationCommandService.notifyReaction(context.setlog().getAuthorPet().getId(),
                    context.activePet().getId(), context.activePet().getNickname(),
                    context.activePet().getProfileAsset() == null ? null : context.activePet().getProfileAsset().getId(),
                    type == ReactionType.LIKE ? NotificationType.SETLOG_LIKE : NotificationType.SETLOG_CUTE,
                    NotificationTargetType.SETLOG, setlogId, null, setlogId);
        }
        return toResponse(context.setlog(), type, true);
    }

    /**
     * Removes a reaction idempotently. A missing reaction still returns a
     * successful response with {@code reacted=false}.
     */
    @Transactional
    public SetlogReactionResponse removeReaction(
            Long userId,
            Long setlogId,
            ReactionType type
    ) {
        ReactionContext context = requireReactionContext(userId, setlogId);
        setlogReactionRepository
                .findBySetlog_IdAndReactorPet_IdAndType(
                        setlogId,
                        context.activePet().getId(),
                        type
                )
                .ifPresent(reaction -> {
                    setlogReactionRepository.delete(reaction);
                    context.setlog().decrementReaction(type);
                });
        return toResponse(context.setlog(), type, false);
    }

    private ReactionContext requireReactionContext(
            Long userId,
            Long setlogId
    ) {
        ActivePetContext activePet =
                activePetQueryService.requireActivePet(userId);
        Long targetPetId = setlogRepository.findAuthorPetIdById(setlogId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.SETLOG_NOT_FOUND)
                );
        InteractionPairContext pair =
                interactionPairLockService.lockInteractionPair(
                        activePet.petId(),
                        targetPetId
                );
        validateLockedPair(userId, activePet, pair);

        Long authorOwnerId = pair.targetUser().userId();
        if (activePet.ownerUserId().equals(authorOwnerId)) {
            throw new BusinessException(
                    ErrorCode.SETLOG_SELF_REACTION_FORBIDDEN
            );
        }
        if (blockRelationshipQueryService.existsBlockBetween(
                pair.sourceUser().userId(),
                authorOwnerId
        )) {
            throw new BusinessException(ErrorCode.BLOCKED_USER);
        }

        Setlog setlog = setlogRepository.findInteractableByIdForUpdate(
                        setlogId,
                        SetlogStatus.VISIBLE,
                        PLAYABLE_MEDIA_STATUSES
                )
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.SETLOG_NOT_FOUND)
                );
        if (!Objects.equals(setlog.getAuthorPet().getId(), targetPetId)) {
            throw new BusinessException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
        }

        Pet reactorPet = petRepository.findById(activePet.petId())
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED)
                );
        return new ReactionContext(setlog, reactorPet);
    }

    private void validateLockedPair(
            Long userId,
            ActivePetContext activePet,
            InteractionPairContext pair
    ) {
        if (!Objects.equals(userId, pair.sourceUser().userId())
                || !Objects.equals(
                        activePet.ownerUserId(),
                        pair.sourceUser().userId()
                )) {
            throw new BusinessException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
        }
        if (pair.sourceUser().accountStatus() != AccountStatus.ACTIVE
                || !Objects.equals(
                        pair.sourceUser().activePetId(),
                        activePet.petId()
                )
                || pair.sourcePet().status() != PetStatus.ACTIVE
                || pair.sourcePet().deletedAt() != null) {
            throw new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED);
        }
        if (pair.targetUser().accountStatus() != AccountStatus.ACTIVE
                || pair.targetPet().status() != PetStatus.ACTIVE
                || pair.targetPet().deletedAt() != null) {
            throw new BusinessException(ErrorCode.SETLOG_NOT_FOUND);
        }
    }

    private SetlogReactionResponse toResponse(
            Setlog setlog,
            ReactionType type,
            boolean reacted
    ) {
        return new SetlogReactionResponse(
                setlog.getId(),
                type,
                reacted,
                setlog.getCuteCount(),
                setlog.getLikeCount()
        );
    }

    private record ReactionContext(
            Setlog setlog,
            Pet activePet
    ) {
    }
}
