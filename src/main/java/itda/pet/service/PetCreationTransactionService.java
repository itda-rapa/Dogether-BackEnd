package itda.pet.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import itda.petverification.PetVerificationEvidence;
import itda.petverification.domain.PetVerification;
import itda.petverification.repository.PetVerificationRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PetCreationTransactionService {

    private static final long MAX_UNDELETED_PET_COUNT = 5;

    private final UserRepository userRepository;
    private final PetRepository petRepository;
    private final PetVerificationRepository verificationRepository;

    public PetCreationTransactionService(
            UserRepository userRepository,
            PetRepository petRepository,
            PetVerificationRepository verificationRepository
    ) {
        this.userRepository = userRepository;
        this.petRepository = petRepository;
        this.verificationRepository = verificationRepository;
    }

    @Transactional
    public PetCreationOutcome createAttempt(
            Long userId,
            PetCreateCommand command,
            String publicTag
    ) {
        return createAttempt(userId, command, publicTag, null);
    }

    @Transactional
    public PetCreationOutcome createAttempt(
            Long userId,
            PetCreateCommand command,
            String publicTag,
            PetVerificationEvidence evidence
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
        if (evidence != null) {
            try {
                verificationRepository.saveAndFlush(
                        PetVerification.create(savedPet, evidence.toEntityEvidence())
                );
            } catch (DataIntegrityViolationException exception) {
                if (isVerificationUniqueViolation(exception)) {
                    throw new BusinessException(ErrorCode.PET_VERIFICATION_CONFLICT);
                }
                throw exception;
            }
        }

        return new PetCreationOutcome(
                savedPet.getId(),
                undeletedPetCount == 0
        );
    }

    private boolean isVerificationUniqueViolation(DataIntegrityViolationException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                return "uk_pet_verifications_pet".equalsIgnoreCase(name)
                        || "uk_pet_verifications_registration_number_hmac".equalsIgnoreCase(name);
            }
            current = current.getCause();
        }
        return false;
    }
}
