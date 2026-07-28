package itda.pet.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PetCreationService {

    private static final int PUBLIC_TAG_SAVE_ATTEMPTS = 5;
    private static final String PUBLIC_TAG_UNIQUE_CONSTRAINT =
            "uk_pets_public_tag";

    private final PetPublicTagGenerator petPublicTagGenerator;
    private final PetCreationTransactionService petCreationTransactionService;

    public PetCreationService(
            PetPublicTagGenerator petPublicTagGenerator,
            PetCreationTransactionService petCreationTransactionService
    ) {
        this.petPublicTagGenerator = petPublicTagGenerator;
        this.petCreationTransactionService = petCreationTransactionService;
    }

    @Transactional(propagation = Propagation.NEVER)
    public PetCreationOutcome create(
            Long userId,
            PetCreateCommand command
    ) {
        for (int attempt = 0; attempt < PUBLIC_TAG_SAVE_ATTEMPTS; attempt++) {
            String publicTag = petPublicTagGenerator.generate(command.nickname());
            try {
                return petCreationTransactionService.createAttempt(
                        userId,
                        command,
                        publicTag
                );
            } catch (DataIntegrityViolationException exception) {
                if (!isPublicTagUniqueConstraintViolation(exception)) {
                    throw exception;
                }
            }
        }

        throw new BusinessException(ErrorCode.PET_PUBLIC_TAG_GENERATION_FAILED);
    }

    private boolean isPublicTagUniqueConstraintViolation(
            DataIntegrityViolationException exception
    ) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException
                    constraintViolation
                    && PUBLIC_TAG_UNIQUE_CONSTRAINT.equalsIgnoreCase(
                    constraintViolation.getConstraintName()
            )) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
