package itda.pet.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivePetSelectionService {

    private final UserRepository userRepository;
    private final PetRepository petRepository;

    public ActivePetSelectionService(
            UserRepository userRepository,
            PetRepository petRepository
    ) {
        this.userRepository = userRepository;
        this.petRepository = petRepository;
    }

    @Transactional
    public void selectActivePet(Long userId, Long petId) {
        if (petId == null) {
            throw new NullPointerException("petId는 null일 수 없습니다.");
        }

        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.USER_NOT_FOUND)
                );
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }

        Pet pet = petRepository.findByIdForUpdate(petId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.PET_NOT_FOUND)
                );
        if (!pet.belongsTo(userId)) {
            throw new BusinessException(ErrorCode.PET_NOT_OWNED);
        }
        if (!pet.isActive() || pet.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.PET_NOT_ACTIVE);
        }

        user.selectActivePet(petId);
    }
}
