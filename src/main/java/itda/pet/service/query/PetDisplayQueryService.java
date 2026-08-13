package itda.pet.service.query;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.Pet;
import itda.pet.domain.PetStatus;
import itda.pet.repository.PetRepository;
import itda.media.service.MediaService;
import itda.user.domain.AccountStatus;
import itda.petverification.PetVerificationBadgeService;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PetDisplayQueryService {

    private final PetRepository petRepository;
    private final MediaService mediaService;
    private final PetVerificationBadgeService badgeService;

    public PetDisplayQueryService(
            PetRepository petRepository,
            MediaService mediaService,
            PetVerificationBadgeService badgeService
    ) {
        this.petRepository = petRepository;
        this.mediaService = mediaService;
        this.badgeService = badgeService;
    }

    @Transactional(readOnly = true)
    public PetDisplaySummary getPetDisplaySummary(Long petId) {
        Pet pet = petRepository.findByIdWithOwnerAndProfileAsset(petId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.PET_NOT_FOUND)
                );

        return toDisplaySummary(pet, badgeService.verifiedAt(pet.getId()));
    }

    @Transactional(readOnly = true)
    public Optional<PetDisplaySummary> findSearchablePetDisplaySummary(
            String publicTag
    ) {
        if (publicTag == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        return petRepository.findSearchableByPublicTag(
                        publicTag,
                        PetStatus.ACTIVE,
                        AccountStatus.ACTIVE
                )
                .map(pet -> toDisplaySummary(pet, badgeService.verifiedAt(pet.getId())));
    }

    @Transactional(readOnly = true)
    public Map<Long, PetDisplaySummary> getPetDisplaySummaries(
            Collection<Long> petIds
    ) {
        if (petIds == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        Set<Long> requestedIds = new LinkedHashSet<>();
        for (Long petId : petIds) {
            if (petId == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            requestedIds.add(petId);
        }

        if (requestedIds.isEmpty()) {
            return Map.of();
        }

        List<Pet> pets = petRepository
                .findAllByIdWithOwnerAndProfileAsset(requestedIds);
        Map<Long, Instant> badges = badgeService.verifiedAtByPetIds(requestedIds);
        Map<Long, PetDisplaySummary> result = new LinkedHashMap<>();
        for (Pet pet : pets) {
            result.put(pet.getId(), toDisplaySummary(pet, badges.get(pet.getId())));
        }

        if (!result.keySet().equals(requestedIds)) {
            throw new BusinessException(ErrorCode.PET_NOT_FOUND);
        }

        return Map.copyOf(result);
    }

    private PetDisplaySummary toDisplaySummary(Pet pet, Instant verifiedAt) {
        return new PetDisplaySummary(
                pet.getId(),
                pet.getOwner().getId(),
                pet.getPublicTag(),
                pet.getNickname(),
                profileUrlOf(pet),
                verifiedAt != null,
                pet.getStatus(),
                pet.getDeletedAt()
        );
    }

    private String profileUrlOf(Pet pet) {
        if (pet.getProfileAsset() == null) {
            return null;
        }
        return mediaService.getPresignedDownloadUrl(
                pet.getProfileAsset().getId()
        ).url();
    }
}
