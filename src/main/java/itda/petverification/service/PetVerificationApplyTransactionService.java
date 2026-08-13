package itda.petverification.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import itda.petverification.PetVerificationEvidence;
import itda.petverification.domain.PetVerification;
import itda.petverification.repository.PetVerificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

@Service
public class PetVerificationApplyTransactionService {
    private final PetRepository petRepository;
    private final PetVerificationRepository verificationRepository;

    public PetVerificationApplyTransactionService(PetRepository petRepository,
                                                  PetVerificationRepository verificationRepository) {
        this.petRepository = petRepository;
        this.verificationRepository = verificationRepository;
    }

    @Transactional
    public void apply(Long userId, Long petId, PetVerificationEvidence evidence) {
        Pet pet = petRepository.findByIdForUpdate(petId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PET_NOT_FOUND));
        if (pet.isDeleted() || pet.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.PET_NOT_FOUND);
        }
        if (!pet.belongsTo(userId)) throw new BusinessException(ErrorCode.PET_NOT_OWNED);
        if (verificationRepository.existsByPet_Id(petId)) {
            throw new BusinessException(ErrorCode.PET_VERIFICATION_CONFLICT);
        }
        try {
            verificationRepository.saveAndFlush(PetVerification.create(pet, evidence.toEntityEvidence()));
        } catch (DataIntegrityViolationException exception) {
            if (isVerificationUniqueViolation(exception)) {
                throw new BusinessException(ErrorCode.PET_VERIFICATION_CONFLICT);
            }
            throw exception;
        }
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
