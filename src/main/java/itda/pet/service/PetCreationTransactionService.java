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
public class PetCreationTransactionService {

    private static final long MAX_UNDELETED_PET_COUNT = 5;

    private final UserRepository userRepository;
    private final PetRepository petRepository;

    public PetCreationTransactionService(
            UserRepository userRepository,
            PetRepository petRepository
    ) {
        this.userRepository = userRepository;
        this.petRepository = petRepository;
    }

    @Transactional
    public PetCreationOutcome createAttempt(
            Long userId,
            PetCreateCommand command,
            String publicTag
    ) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.USER_NOT_FOUND)
                );
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }

        long undeletedPetCount =
                petRepository.countByOwner_IdAndDeletedAtIsNull(userId);
        if (undeletedPetCount >= MAX_UNDELETED_PET_COUNT) {
            throw new BusinessException(ErrorCode.PET_LIMIT_EXCEEDED);
        }

        Pet pet = Pet.register(
                user,
                publicTag,
                command.nickname(),
                command.breedName(),
                command.sex(),
                command.neutered(),
                command.birthDate(),
                command.weightKg(),
                command.sizeCode(),
                command.bio(),
                command.personalityTags(),
                command.careNote()
        );
        Pet savedPet = petRepository.saveAndFlush(pet);

        return new PetCreationOutcome(
                savedPet.getId(),
                undeletedPetCount == 0
        );
    }
}
