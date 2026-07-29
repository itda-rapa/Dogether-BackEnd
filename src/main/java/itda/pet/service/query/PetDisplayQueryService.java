package itda.pet.service.query;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        return toDisplaySummary(pet);
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

        List<Pet> pets = petRepository.findAllById(requestedIds);
        Map<Long, PetDisplaySummary> result = new LinkedHashMap<>();
        for (Pet pet : pets) {
            result.put(pet.getId(), toDisplaySummary(pet));
        }

        if (!result.keySet().equals(requestedIds)) {
            throw new BusinessException(ErrorCode.PET_NOT_FOUND);
        }

        return Map.copyOf(result);
    }

    private PetDisplaySummary toDisplaySummary(Pet pet) {
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
