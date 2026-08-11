package itda.boardpost.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import itda.pet.service.query.ActivePetValidator;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LockedActivePetCommandGuard {

    private final UserRepository userRepository;
    private final PetRepository petRepository;
    private final ActivePetValidator validator;

    public LockedActivePetCommandGuard(
            UserRepository userRepository,
            PetRepository petRepository,
            ActivePetValidator validator
    ) {
        this.userRepository = userRepository;
        this.petRepository = petRepository;
        this.validator = validator;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LockedActor require(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(this::required);
        if (!user.isActive() || !user.hasActivePet()) {
            throw required();
        }
        Pet pet = petRepository.findByIdForUpdate(user.getActivePetId())
                .orElseThrow(this::required);
        if (!validator.isValid(user, pet)) {
            throw required();
        }
        return new LockedActor(
                user.getId(),
                pet.getId(),
                user.getNeighborhoodCode()
        );
    }

    private BusinessException required() {
        return new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED);
    }

    public record LockedActor(Long userId, Long petId, String neighborhoodCode) {
    }
}
