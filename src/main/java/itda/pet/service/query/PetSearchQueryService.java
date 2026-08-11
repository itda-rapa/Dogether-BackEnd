package itda.pet.service.query;

import itda.block.service.BlockRelationshipQueryService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRelationship;
import itda.friend.service.query.FriendRelationshipQueryService;
import itda.pet.dto.PetSearchItemResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PetSearchQueryService {

    private final PetDisplayQueryService petDisplayQueryService;
    private final BlockRelationshipQueryService blockRelationshipQueryService;
    private final ActivePetQueryService activePetQueryService;
    private final FriendRelationshipQueryService friendRelationshipQueryService;

    public PetSearchQueryService(
            PetDisplayQueryService petDisplayQueryService,
            BlockRelationshipQueryService blockRelationshipQueryService,
            ActivePetQueryService activePetQueryService,
            FriendRelationshipQueryService friendRelationshipQueryService
    ) {
        this.petDisplayQueryService = petDisplayQueryService;
        this.blockRelationshipQueryService = blockRelationshipQueryService;
        this.activePetQueryService = activePetQueryService;
        this.friendRelationshipQueryService = friendRelationshipQueryService;
    }

    @Transactional(readOnly = true)
    public Optional<PetSearchItemResponse> search(
            Long authenticatedUserId,
            String normalizedPublicTag
    ) {
        if (authenticatedUserId == null || normalizedPublicTag == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        Optional<PetDisplaySummary> targetResult =
                petDisplayQueryService.findSearchablePetDisplaySummary(
                        normalizedPublicTag
                );
        if (targetResult.isEmpty()) {
            return Optional.empty();
        }

        PetDisplaySummary target = targetResult.get();
        if (authenticatedUserId.equals(target.ownerUserId())) {
            return Optional.empty();
        }
        if (blockRelationshipQueryService.existsBlockBetween(
                authenticatedUserId,
                target.ownerUserId()
        )) {
            return Optional.empty();
        }

        Optional<ActivePetContext> activePet =
                activePetQueryService.findActivePet(authenticatedUserId);
        FriendRelationship relationship = activePet
                .map(source -> relationshipOf(source.petId(), target.petId()))
                .orElse(null);

        return Optional.of(PetSearchItemResponse.from(target, relationship));
    }

    private FriendRelationship relationshipOf(
            Long sourcePetId,
            Long targetPetId
    ) {
        Map<Long, FriendRelationship> relationships =
                friendRelationshipQueryService.getRelationships(
                        sourcePetId,
                        List.of(targetPetId)
                );
        return relationships.getOrDefault(
                targetPetId,
                FriendRelationship.NONE
        );
    }
}
