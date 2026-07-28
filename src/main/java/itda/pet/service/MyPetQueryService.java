package itda.pet.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.Pet;
import itda.pet.dto.PetResponse;
import itda.pet.repository.PetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MyPetQueryService {

    private final PetRepository petRepository;

    public MyPetQueryService(PetRepository petRepository) {
        this.petRepository = petRepository;
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

        return PetResponse.from(pet, pet.getOwner().isActivePet(petId));
    }
}
