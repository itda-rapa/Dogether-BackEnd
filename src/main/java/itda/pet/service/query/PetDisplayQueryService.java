package itda.pet.service.query;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PetDisplayQueryService {

    private final PetRepository petRepository;

    public PetDisplayQueryService(PetRepository petRepository) {
        this.petRepository = petRepository;
    }

    @Transactional(readOnly = true)
    public PetDisplaySummary getPetDisplaySummary(Long petId) {
        Pet pet = petRepository.findById(petId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.PET_NOT_FOUND)
                );

        return new PetDisplaySummary(
                pet.getId(),
                pet.getOwner().getId(),
                pet.getPublicTag(),
                pet.getNickname(),
                null,
                false,
                pet.getStatus(),
                pet.getDeletedAt()
        );
    }
}
