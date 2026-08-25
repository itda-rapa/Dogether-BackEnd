package itda.pet.service.query;

import itda.block.service.BlockRelationshipQueryService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRelationship;
import itda.friend.service.query.FriendRelationshipQueryService;
import itda.media.domain.Media;
import itda.media.service.MediaService;
import itda.pet.domain.Pet;
import itda.pet.domain.PetStatus;
import itda.pet.dto.PetPublicProfileResponse;
import itda.pet.repository.PetRepository;
import itda.petverification.PetVerificationBadgeService;
import itda.user.domain.AccountStatus;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PetPublicProfileQueryService {

    private final PetRepository petRepository;
    private final BlockRelationshipQueryService blockRelationshipQueryService;
    private final ActivePetQueryService activePetQueryService;
    private final FriendRelationshipQueryService friendRelationshipQueryService;
    private final MediaService mediaService;
    private final PetVerificationBadgeService badgeService;
    private final PetHelpfulReceivedCountQueryService helpfulReceivedCountQueryService;

    public PetPublicProfileQueryService(
            PetRepository petRepository,
            BlockRelationshipQueryService blockRelationshipQueryService,
            ActivePetQueryService activePetQueryService,
            FriendRelationshipQueryService friendRelationshipQueryService,
            MediaService mediaService,
            PetVerificationBadgeService badgeService,
            PetHelpfulReceivedCountQueryService helpfulReceivedCountQueryService
    ) {
        this.petRepository = petRepository;
        this.blockRelationshipQueryService = blockRelationshipQueryService;
        this.activePetQueryService = activePetQueryService;
        this.friendRelationshipQueryService = friendRelationshipQueryService;
        this.mediaService = mediaService;
        this.badgeService = badgeService;
        this.helpfulReceivedCountQueryService = helpfulReceivedCountQueryService;
    }

    @Transactional(readOnly = true)
    public PetPublicProfileResponse getPublicProfile(
            Long viewerUserId,
            Long petId
    ) {
        Pet pet = petRepository.findPublicProfileById(
                        petId,
                        PetStatus.ACTIVE,
                        AccountStatus.ACTIVE
                )
                .orElseThrow(PetPublicProfileQueryService::petNotFound);

        Long targetOwnerId = pet.getOwner().getId();
        if (blockRelationshipQueryService.existsBlockBetween(
                viewerUserId,
                targetOwnerId
        )) {
            throw petNotFound();
        }

        FriendRelationship relationship = relationshipOf(viewerUserId, pet);
        String profileUrl = profileUrlOf(pet);
        boolean verified = badgeService.verifiedAt(petId) != null;
        long helpfulReceivedCount = helpfulReceivedCountQueryService.countForPet(petId);

        return new PetPublicProfileResponse(
                petId,
                pet.getPublicTag(),
                pet.getNickname(),
                profileUrl,
                verified,
                pet.getBreedName(),
                pet.getSex(),
                pet.getNeutered(),
                pet.getBirthDate(),
                pet.getSizeCode(),
                pet.getBio(),
                pet.getPersonalityTags(),
                helpfulReceivedCount,
                relationship
        );
    }

    private FriendRelationship relationshipOf(Long viewerUserId, Pet targetPet) {
        if (targetPet.belongsTo(viewerUserId)) {
            return null;
        }

        return activePetQueryService.findActivePet(viewerUserId)
                .map(activePet -> relationshipsOf(activePet.petId(), targetPet.getId()))
                .orElse(null);
    }

    private FriendRelationship relationshipsOf(Long sourcePetId, Long targetPetId) {
        return friendRelationshipQueryService.getRelationships(
                        sourcePetId,
                        List.of(targetPetId)
                )
                .getOrDefault(targetPetId, FriendRelationship.NONE);
    }

    private String profileUrlOf(Pet pet) {
        Media profileAsset = pet.getProfileAsset();
        if (profileAsset == null) {
            return null;
        }
        Map<Long, MediaService.PresignedDownloadUrl> urls =
                mediaService.getPresignedDownloadUrls(List.of(profileAsset));
        return urls.get(profileAsset.getId()).url();
    }

    private static BusinessException petNotFound() {
        return new BusinessException(ErrorCode.PET_NOT_FOUND);
    }
}
