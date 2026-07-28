package itda.pet.service.query;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivePetQueryService {

    private final UserRepository userRepository;
    private final PetRepository petRepository;

    public ActivePetQueryService(
            UserRepository userRepository,
            PetRepository petRepository
    ) {
        this.userRepository = userRepository;
        this.petRepository = petRepository;
    }

    @Transactional(readOnly = true)
    public ActivePetContext requireActivePet(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(ActivePetQueryService::activePetRequired);
        if (!user.isActive() || !user.hasActivePet()) {
            throw activePetRequired();
        }

        Pet pet = petRepository.findById(user.getActivePetId())
                .orElseThrow(ActivePetQueryService::activePetRequired);
        if (!pet.belongsTo(userId)
                || !pet.isActive()
                || pet.getDeletedAt() != null) {
            throw activePetRequired();
        }

        return new ActivePetContext(
                pet.getId(),
                pet.getOwner().getId(),
                pet.getPublicTag(),
                pet.getNickname(),
                null,
                false
        );
    }

    private static BusinessException activePetRequired() {
        return new BusinessException(ErrorCode.ACTIVE_PET_REQUIRED);
    }
}
