package itda.setlog.service;

import itda.block.service.BlockRelationshipQueryService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.MediaStatus;
import itda.pet.domain.Pet;
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
import java.util.List;
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
    private final BlockRelationshipQueryService blockRelationshipQueryService;

    public SetlogReactionService(
            SetlogRepository setlogRepository,
            SetlogReactionRepository setlogReactionRepository,
            PetRepository petRepository,
            ActivePetQueryService activePetQueryService,
            BlockRelationshipQueryService blockRelationshipQueryService
    ) {
        this.setlogRepository = setlogRepository;
        this.setlogReactionRepository = setlogReactionRepository;
        this.petRepository = petRepository;
        this.activePetQueryService = activePetQueryService;
        this.blockRelationshipQueryService = blockRelationshipQueryService;
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
        Setlog setlog = setlogRepository.findVisibleSeedByIdForUpdate(
                        setlogId,
                        SetlogStatus.VISIBLE,
                        PLAYABLE_MEDIA_STATUSES
                )
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.SETLOG_NOT_FOUND)
                );

        Long authorOwnerId = setlog.getAuthorPet().getOwner().getId();
        if (activePet.ownerUserId().equals(authorOwnerId)) {
            throw new BusinessException(
                    ErrorCode.SETLOG_SELF_REACTION_FORBIDDEN
            );
        }
        if (blockRelationshipQueryService.existsBlockBetween(
                activePet.ownerUserId(),
                authorOwnerId
        )) {
            throw new BusinessException(ErrorCode.BLOCKED_USER);
        }

        Pet reactorPet = petRepository.findById(activePet.petId())
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED)
                );
        return new ReactionContext(setlog, reactorPet);
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
