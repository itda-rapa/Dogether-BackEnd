package itda.interaction.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.dto.LockedPetContext;
import itda.interaction.dto.LockedUserContext;
import itda.pet.domain.PetStatus;
import itda.user.domain.AccountStatus;
import org.springframework.stereotype.Service;

/**
 * Target policy for cross-domain interactions, validated from a locked pair snapshot.
 *
 * <p>Target state is intentionally hidden from callers. An inactive Pet, a deleted Pet, an
 * inactive owner, or an owner that no longer matches the snapshot all have the same external
 * result. Callers must pass the {@link InteractionPairContext} returned by
 * {@link InteractionPairLockService#lockInteractionPair} so that the state check and the
 * subsequent room creation observe the same locked state. This service never re-reads the
 * Pet/User tables itself.
 */
@Service
public class InteractionTargetQueryService {

    public void requireActiveTargets(
            InteractionPairContext pair,
            Long sourceOwnerUserId,
            Long targetOwnerUserId
    ) {
        if (sourceOwnerUserId == null || targetOwnerUserId == null) {
            throw notFound();
        }
        if (!isActive(pair.sourcePet(), pair.sourceUser(), sourceOwnerUserId)
                || !isActive(pair.targetPet(), pair.targetUser(), targetOwnerUserId)) {
            throw notFound();
        }
    }

    private boolean isActive(
            LockedPetContext pet,
            LockedUserContext owner,
            Long expectedOwnerUserId
    ) {
        return pet.status() == PetStatus.ACTIVE
                && pet.deletedAt() == null
                && owner.accountStatus() == AccountStatus.ACTIVE
                && expectedOwnerUserId.equals(pet.ownerUserId());
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND);
    }
}
