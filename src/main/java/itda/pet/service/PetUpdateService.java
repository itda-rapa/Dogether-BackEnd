package itda.pet.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.Pet;
import itda.pet.domain.PetStatus;
import itda.pet.dto.PetResponse;
import itda.pet.repository.PetRepository;
import itda.media.service.MediaService;
import itda.petverification.PetVerificationBadgeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PetUpdateService {

    private final PetRepository petRepository;
    private final MediaService mediaService;
    private final PetVerificationBadgeService badgeService;

    public PetUpdateService(
            PetRepository petRepository,
            MediaService mediaService,
            PetVerificationBadgeService badgeService
    ) {
        this.petRepository = petRepository;
        this.mediaService = mediaService;
        this.badgeService = badgeService;
    }

    @Transactional
    public PetResponse update(
            Long authenticatedUserId,
            Long petId,
            PetUpdateCommand command
    ) {
        validateCommand(command);

        Pet pet = petRepository.findByIdWithOwner(petId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.PET_NOT_FOUND)
                );
        if (pet.getStatus() == PetStatus.DELETED
                || pet.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.PET_NOT_FOUND);
        }
        if (!pet.belongsTo(authenticatedUserId)) {
            throw new BusinessException(ErrorCode.PET_NOT_OWNED);
        }

        applyUpdate(pet, command);

        return PetResponse.from(pet, pet.getOwner().isActivePet(petId),
                profileUrlOf(pet), badgeService.verifiedAt(petId));
    }

    private void validateCommand(PetUpdateCommand command) {
        if (command == null
                || !command.hasAnyPresentField()
                || (command.nickname().present()
                && command.nickname().value() == null)
                || (command.personalityTags().present()
                && command.personalityTags().value() == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private void applyUpdate(Pet pet, PetUpdateCommand command) {
        if (command.nickname().present()) {
            pet.changeNickname(command.nickname().value());
        }
        if (command.breedName().present()) {
            pet.changeBreedName(command.breedName().value());
        }
        if (command.sex().present()) {
            pet.changeSex(command.sex().value());
        }
        if (command.neutered().present()) {
            pet.changeNeutered(command.neutered().value());
        }
        if (command.birthDate().present()) {
            pet.changeBirthDate(command.birthDate().value());
        }
        if (command.weightKg().present()) {
            pet.changeWeightKg(command.weightKg().value());
        }
        if (command.sizeCode().present()) {
            pet.changeSizeCode(command.sizeCode().value());
        }
        if (command.bio().present()) {
            pet.changeBio(command.bio().value());
        }
        if (command.personalityTags().present()) {
            pet.changePersonalityTags(command.personalityTags().value());
        }
        if (command.careNote().present()) {
            pet.changeCareNote(command.careNote().value());
        }
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
