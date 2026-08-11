package itda.pet.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.Media;
import itda.media.domain.MediaStatus;
import itda.media.domain.MediaType;
import itda.media.repository.MediaRepository;
import itda.media.service.MediaService;
import itda.pet.domain.Pet;
import itda.pet.domain.PetStatus;
import itda.pet.dto.PetResponse;
import itda.pet.repository.PetRepository;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PetProfileImageService {

    private static final Set<MediaStatus> USABLE_MEDIA_STATUSES =
            EnumSet.of(MediaStatus.UPLOADED, MediaStatus.COMPLETED);

    private final PetRepository petRepository;
    private final MediaRepository mediaRepository;
    private final MediaService mediaService;

    public PetProfileImageService(
            PetRepository petRepository,
            MediaRepository mediaRepository,
            MediaService mediaService
    ) {
        this.petRepository = petRepository;
        this.mediaRepository = mediaRepository;
        this.mediaService = mediaService;
    }

    @Transactional
    public PetResponse setInitialProfileImage(
            Long authenticatedUserId,
            Long petId,
            Long mediaId
    ) {
        Pet pet = petRepository.findByIdWithOwnerAndProfileAsset(petId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.PET_NOT_FOUND)
                );
        validatePet(pet, authenticatedUserId);
        if (pet.getProfileAsset() != null) {
            throw new BusinessException(
                    ErrorCode.PET_PROFILE_IMAGE_ALREADY_SET
            );
        }

        Media media = mediaRepository.findByIdAndDeletedAtIsNull(mediaId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.MEDIA_NOT_FOUND)
                );
        validateMedia(media, authenticatedUserId);

        pet.setInitialProfileAsset(media);
        return PetResponse.from(
                pet,
                pet.getOwner().isActivePet(petId),
                mediaService.getPresignedDownloadUrl(mediaId).url()
        );
    }

    private void validatePet(Pet pet, Long authenticatedUserId) {
        if (pet.getStatus() == PetStatus.DELETED
                || pet.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.PET_NOT_FOUND);
        }
        if (!pet.belongsTo(authenticatedUserId)) {
            throw new BusinessException(ErrorCode.PET_NOT_OWNED);
        }
    }

    private void validateMedia(Media media, Long authenticatedUserId) {
        if (!authenticatedUserId.equals(media.getUserId())) {
            throw new BusinessException(ErrorCode.MEDIA_NOT_OWNED);
        }
        if (media.getMediaType() != MediaType.IMAGE) {
            throw new BusinessException(ErrorCode.INVALID_MEDIA_TYPE);
        }
        if (!USABLE_MEDIA_STATUSES.contains(media.getStatus())) {
            throw new BusinessException(ErrorCode.MEDIA_NOT_UPLOADED);
        }
    }
}
