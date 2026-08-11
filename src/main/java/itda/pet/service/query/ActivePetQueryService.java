package itda.pet.service.query;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivePetQueryService {

    private final UserRepository userRepository;
    private final PetRepository petRepository;
    private final ActivePetValidator activePetValidator;

    public ActivePetQueryService(
            UserRepository userRepository,
            PetRepository petRepository,
            ActivePetValidator activePetValidator
    ) {
        this.userRepository = userRepository;
        this.petRepository = petRepository;
        this.activePetValidator = activePetValidator;
    }

    @Transactional(readOnly = true)
    public ActivePetContext requireActivePet(Long userId) {
        return findActivePetContext(userId)
                .orElseThrow(ActivePetQueryService::activePetRequired);
    }

    @Transactional(readOnly = true)
    public Optional<ActivePetContext> findActivePet(Long userId) {
        return findActivePetContext(userId);
    }

    private Optional<ActivePetContext> findActivePetContext(Long userId) {
        Optional<User> userResult = userRepository.findById(userId);
        if (userResult.isEmpty()) {
            return Optional.empty();
        }

        User user = userResult.get();
        // Account state is determined from the User row alone. Besides avoiding an
        // unnecessary Pet lookup, this preserves the query contract for inactive users.
        if (!user.isActive() || !user.hasActivePet()) {
            return Optional.empty();
        }

        Optional<Pet> petResult = petRepository.findById(user.getActivePetId());
        if (petResult.isEmpty()) {
            return Optional.empty();
        }

        Pet pet = petResult.get();
        if (!activePetValidator.isValid(user, pet)) {
            return Optional.empty();
        }

        return Optional.of(new ActivePetContext(
                pet.getId(),
                pet.getOwner().getId(),
                pet.getPublicTag(),
                pet.getNickname(),
                null,
                false
        ));
    }

    private static BusinessException activePetRequired() {
        return new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED);
    }
}
