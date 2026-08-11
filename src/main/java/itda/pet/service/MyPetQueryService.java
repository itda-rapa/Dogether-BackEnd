package itda.pet.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.Pet;
import itda.pet.domain.PetStatus;
import itda.pet.dto.PetResponse;
import itda.pet.repository.PetRepository;
import itda.media.service.MediaService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MyPetQueryService {

    private final PetRepository petRepository;
    private final MediaService mediaService;

    public MyPetQueryService(
            PetRepository petRepository,
            MediaService mediaService
    ) {
        this.petRepository = petRepository;
        this.mediaService = mediaService;
    }

    @Transactional(readOnly = true)
    public PetResponse getMyPet(Long userId, Long petId) {
        Pet pet = petRepository.findById(petId)
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
                profileUrlOf(pet)
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
        return petRepository.findMyPetsOrdered(userId)
                .stream()
                .map(pet -> PetResponse.from(
                                pet,
                                pet.getOwner().isActivePet(pet.getId()),
                                profileUrlOf(pet)
                        )
                )
                .toList();
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
