package itda.interaction.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.dto.LockedPetContext;
import itda.interaction.dto.LockedUserContext;
import itda.pet.domain.PetStatus;
import itda.pet.repository.PetRepository;
import itda.pet.repository.PetRepository.LockedPetRow;
import itda.pet.repository.PetRepository.PetOwnerRow;
import itda.user.domain.AccountStatus;
import itda.user.repository.UserRepository;
import itda.user.repository.UserRepository.LockedUserRow;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serializes cross-domain interactions involving a pair of Pets.
 *
 * <p>This service owns only structural validation and lock acquisition. Callers decide whether
 * the captured User and Pet states are valid for their domain operation.
 */
@Service
public class InteractionPairLockService {

    private final UserRepository userRepository;
    private final PetRepository petRepository;

    public InteractionPairLockService(
            UserRepository userRepository,
            PetRepository petRepository
    ) {
        this.userRepository = userRepository;
        this.petRepository = petRepository;
    }

    /**
     * Locks distinct owners first and distinct Pets second, each in ascending ID order.
     *
     * <p>The method must join an existing write transaction so that every acquired lock survives
     * until the caller commits or rolls back.
     *
     * <p>Structural lookup failures are reported as follows:
     * <ul>
     *     <li>A Pet missing from the initial owner projection is {@link ErrorCode#PET_NOT_FOUND};
     *     this means the requested Pet could not be found at the initial lookup.</li>
     *     <li>An owner User or Pet present in the projection but missing from its
     *     {@code FOR UPDATE} result is {@link ErrorCode#CONCURRENT_UPDATE_CONFLICT}.</li>
     *     <li>A projected owner that differs from the owner of the locked Pet is also
     *     {@link ErrorCode#CONCURRENT_UPDATE_CONFLICT}. These cases indicate that the structure
     *     may have changed between projection and lock acquisition.</li>
     * </ul>
     *
     * <p>Account and Pet state policy, active Pet selection, same-owner handling, and whether a
     * Block or Friend operation is allowed remain the caller's responsibility. Unexpected enum
     * values are allowed to fail at enum conversion and are not hidden as a concurrency conflict.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public InteractionPairContext lockInteractionPair(
            Long sourcePetId,
            Long targetPetId
    ) {
        if (sourcePetId == null || targetPetId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        Set<Long> expectedPetIds = sortedDistinctIds(sourcePetId, targetPetId);
        Map<Long, Long> projectedOwnerIds = loadProjectedOwnerIds(expectedPetIds);
        Set<Long> expectedUserIds = new TreeSet<>(projectedOwnerIds.values());

        Map<Long, LockedUserContext> lockedUsers = lockUsers(expectedUserIds);
        Map<Long, LockedPetContext> lockedPets =
                lockPets(expectedPetIds, projectedOwnerIds);

        LockedPetContext sourcePet = lockedPets.get(sourcePetId);
        LockedPetContext targetPet = lockedPets.get(targetPetId);
        return new InteractionPairContext(
                lockedUsers.get(sourcePet.ownerUserId()),
                lockedUsers.get(targetPet.ownerUserId()),
                sourcePet,
                targetPet
        );
    }

    private Set<Long> sortedDistinctIds(Long firstId, Long secondId) {
        Set<Long> ids = new TreeSet<>();
        ids.add(firstId);
        ids.add(secondId);
        return ids;
    }

    private Map<Long, Long> loadProjectedOwnerIds(Set<Long> expectedPetIds) {
        List<PetOwnerRow> ownerRows = petRepository.findOwnerRows(expectedPetIds);
        Map<Long, Long> ownerIdsByPetId = new HashMap<>();
        for (PetOwnerRow row : ownerRows) {
            if (row.getPetId() != null && row.getOwnerUserId() != null) {
                ownerIdsByPetId.put(row.getPetId(), row.getOwnerUserId());
            }
        }
        if (!ownerIdsByPetId.keySet().equals(expectedPetIds)) {
            throw new BusinessException(ErrorCode.PET_NOT_FOUND);
        }
        return ownerIdsByPetId;
    }

    private Map<Long, LockedUserContext> lockUsers(Set<Long> expectedUserIds) {
        Map<Long, LockedUserContext> lockedUsers = new HashMap<>();
        for (Long userId : expectedUserIds) {
            LockedUserRow row = userRepository.findLockedUserRow(userId)
                    .orElseThrow(InteractionPairLockService::concurrentUpdateConflict);
            if (!userId.equals(row.getUserId())) {
                throw concurrentUpdateConflict();
            }
            lockedUsers.put(userId, new LockedUserContext(
                    row.getUserId(),
                    AccountStatus.valueOf(row.getAccountStatus()),
                    row.getActivePetId(),
                    row.getPublicTag()
            ));
        }
        if (!lockedUsers.keySet().equals(expectedUserIds)) {
            throw concurrentUpdateConflict();
        }
        return lockedUsers;
    }

    private Map<Long, LockedPetContext> lockPets(
            Set<Long> expectedPetIds,
            Map<Long, Long> projectedOwnerIds
    ) {
        Map<Long, LockedPetContext> lockedPets = new HashMap<>();
        for (Long petId : expectedPetIds) {
            LockedPetRow row = petRepository.findLockedPetRow(petId)
                    .orElseThrow(InteractionPairLockService::concurrentUpdateConflict);
            if (!petId.equals(row.getPetId())
                    || !projectedOwnerIds.get(petId).equals(row.getOwnerUserId())) {
                throw concurrentUpdateConflict();
            }
            lockedPets.put(petId, new LockedPetContext(
                    row.getPetId(),
                    row.getOwnerUserId(),
                    PetStatus.valueOf(row.getStatus()),
                    row.getDeletedAt()
            ));
        }
        if (!lockedPets.keySet().equals(expectedPetIds)) {
            throw concurrentUpdateConflict();
        }
        return lockedPets;
    }

    private static BusinessException concurrentUpdateConflict() {
        return new BusinessException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
    }
}
