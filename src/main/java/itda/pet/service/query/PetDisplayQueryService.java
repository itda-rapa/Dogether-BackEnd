package itda.pet.service.query;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.Media;
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
import java.util.Objects;
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

        return toDisplaySummary(
                pet,
                badgeService.verifiedAt(pet.getId()),
                profileDownloads(List.of(pet))
        );
    }

    /** Immutable notification facts need the asset identifier, not a presigned URL. */
    @Transactional(readOnly = true)
    public Long getProfileAssetId(Long petId) {
        return petRepository.findByIdWithOwnerAndProfileAsset(petId)
                .map(pet -> pet.getProfileAsset() == null ? null : pet.getProfileAsset().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PET_NOT_FOUND));
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
                .map(pet -> toDisplaySummary(
                        pet,
                        badgeService.verifiedAt(pet.getId()),
                        profileDownloads(List.of(pet))
                ));
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
        Map<Long, MediaService.PresignedDownloadUrl> profileDownloads = profileDownloads(pets);
        Map<Long, PetDisplaySummary> result = new LinkedHashMap<>();
        for (Pet pet : pets) {
            result.put(pet.getId(), toDisplaySummary(
                    pet,
                    badges.get(pet.getId()),
                    profileDownloads
            ));
        }

        if (!result.keySet().equals(requestedIds)) {
            throw new BusinessException(ErrorCode.PET_NOT_FOUND);
        }

        return Map.copyOf(result);
    }

    private PetDisplaySummary toDisplaySummary(
            Pet pet,
            Instant verifiedAt,
            Map<Long, MediaService.PresignedDownloadUrl> profileDownloads
    ) {
        return new PetDisplaySummary(
                pet.getId(),
                pet.getOwner().getId(),
                pet.getPublicTag(),
                pet.getNickname(),
                profileUrlOf(pet, profileDownloads),
                verifiedAt != null,
                pet.getStatus(),
                pet.getDeletedAt()
        );
    }

    private Map<Long, MediaService.PresignedDownloadUrl> profileDownloads(Collection<Pet> pets) {
        Map<Long, Media> assets = new LinkedHashMap<>();
        for (Pet pet : pets) {
            Media asset = pet.getProfileAsset();
            if (asset != null) {
                assets.putIfAbsent(asset.getId(), asset);
            }
        }
        if (assets.isEmpty()) {
            return Map.of();
        }
        return mediaService.getPresignedDownloadUrls(assets.values());
    }

    private String profileUrlOf(
            Pet pet,
            Map<Long, MediaService.PresignedDownloadUrl> profileDownloads
    ) {
        if (pet.getProfileAsset() == null) {
            return null;
        }
        return Objects.requireNonNull(profileDownloads.get(pet.getProfileAsset().getId())).url();
    }
}
