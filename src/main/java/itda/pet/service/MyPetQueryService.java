package itda.pet.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.Media;
import itda.pet.domain.Pet;
import itda.pet.domain.PetStatus;
import itda.pet.dto.PetResponse;
import itda.pet.repository.PetRepository;
import itda.media.service.MediaService;
import itda.petverification.PetVerificationBadgeService;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import itda.pet.service.query.PetHelpfulReceivedCountQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MyPetQueryService {

    private final PetRepository petRepository;
    private final MediaService mediaService;
    private final PetVerificationBadgeService badgeService;
    private final PetHelpfulReceivedCountQueryService helpfulReceivedCounts;

    public MyPetQueryService(
            PetRepository petRepository,
            MediaService mediaService,
            PetVerificationBadgeService badgeService,
            PetHelpfulReceivedCountQueryService helpfulReceivedCounts
    ) {
        this.petRepository = petRepository;
        this.mediaService = mediaService;
        this.badgeService = badgeService;
        this.helpfulReceivedCounts = helpfulReceivedCounts;
    }

    @Transactional(readOnly = true)
    public PetResponse getMyPet(Long userId, Long petId) {
        Pet pet = petRepository.findByIdWithOwnerAndProfileAsset(petId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.PET_NOT_FOUND)
                );
        if (pet.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.PET_NOT_FOUND);
        }
        if (!pet.belongsTo(userId)) {
            throw new BusinessException(ErrorCode.PET_NOT_OWNED);
        }

        return PetResponse.from(
                pet,
                pet.getOwner().isActivePet(petId),
                profileUrlOf(pet, profileDownloads(List.of(pet))),
                verifiedAt(petId),
                helpfulReceivedCount(petId)
        );
    }

    @Transactional(readOnly = true)
    public void requireOwnedUndeletedPet(Long userId, Long petId) {
        Pet pet = petRepository.findById(petId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.PET_NOT_FOUND)
                );
        if (pet.getStatus() == PetStatus.DELETED
                || pet.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.PET_NOT_FOUND);
        }
        if (!pet.belongsTo(userId)) {
            throw new BusinessException(ErrorCode.PET_NOT_OWNED);
        }
    }

    @Transactional(readOnly = true)
    public List<PetResponse> getMyPets(Long userId) {
        List<Pet> pets = petRepository.findMyPetsOrdered(userId);
        Map<Long, Instant> badges = badgeService.verifiedAtByPetIds(
                pets.stream().map(Pet::getId).toList());
        Map<Long, Long> helpfulCounts = helpfulReceivedCounts.countForPets(
                pets.stream().map(Pet::getId).toList()
        );
        Map<Long, MediaService.PresignedDownloadUrl> profileDownloads =
                profileDownloads(pets);
        return pets.stream()
                .map(pet -> PetResponse.from(
                                pet,
                                pet.getOwner().isActivePet(pet.getId()),
                                profileUrlOf(pet, profileDownloads),
                                badges.get(pet.getId()),
                                helpfulCounts.getOrDefault(pet.getId(), 0L)
                        )
                )
                .toList();
    }

    private Map<Long, MediaService.PresignedDownloadUrl> profileDownloads(
            Collection<Pet> pets
    ) {
        Map<Long, Media> profileAssets = new LinkedHashMap<>();
        for (Pet pet : pets) {
            Media profileAsset = pet.getProfileAsset();
            if (profileAsset != null) {
                profileAssets.putIfAbsent(profileAsset.getId(), profileAsset);
            }
        }
        if (profileAssets.isEmpty()) {
            return Map.of();
        }
        return mediaService.getPresignedDownloadUrls(profileAssets.values());
    }

    private String profileUrlOf(
            Pet pet,
            Map<Long, MediaService.PresignedDownloadUrl> profileDownloads
    ) {
        if (pet.getProfileAsset() == null) {
            return null;
        }
        return profileDownloads.get(pet.getProfileAsset().getId()).url();
    }

    private Instant verifiedAt(Long petId) { return badgeService.verifiedAt(petId); }

    private long helpfulReceivedCount(Long petId) {
        return helpfulReceivedCounts.countForPet(petId);
    }
}
