package itda.pet.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import itda.petverification.PetVerificationFlowType;
import itda.petverification.PetVerificationRedisStore;

@Service
@Slf4j
public class PetCreationService {

    private static final int PUBLIC_TAG_SAVE_ATTEMPTS = 5;
    private static final String PUBLIC_TAG_UNIQUE_CONSTRAINT =
            "uk_pets_public_tag";

    private final PetPublicTagGenerator petPublicTagGenerator;
    private final PetCreationTransactionService petCreationTransactionService;
    private final ActivePetAssignmentTransactionService
            activePetAssignmentTransactionService;
    private final PetVerificationRedisStore verificationRedisStore;

    public PetCreationService(
            PetPublicTagGenerator petPublicTagGenerator,
            PetCreationTransactionService petCreationTransactionService,
            ActivePetAssignmentTransactionService
                    activePetAssignmentTransactionService,
            PetVerificationRedisStore verificationRedisStore
    ) {
        this.petPublicTagGenerator = petPublicTagGenerator;
        this.petCreationTransactionService = petCreationTransactionService;
        this.activePetAssignmentTransactionService =
                activePetAssignmentTransactionService;
        this.verificationRedisStore = verificationRedisStore;
    }

    @Transactional(propagation = Propagation.NEVER)
    public PetCreationResult create(
            Long userId,
            PetCreateCommand command
    ) {
        return create(userId, command, null);
    }

    @Transactional(propagation = Propagation.NEVER)
    public PetCreationResult create(Long userId, PetCreateCommand command, String rawVerificationToken) {
        if (rawVerificationToken != null && rawVerificationToken.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        PetVerificationRedisStore.Reservation reservation = rawVerificationToken == null
                ? null : verificationRedisStore.reserve(rawVerificationToken, userId,
                PetVerificationFlowType.PET_CREATE, null);
        PetCreationOutcome outcome;
        try {
            outcome = createPetWithPublicTagRetry(userId, command,
                    reservation == null ? null : reservation.evidence());
        } catch (RuntimeException exception) {
            if (reservation != null) {
                try { verificationRedisStore.release(rawVerificationToken, reservation.reservationId()); }
                catch (RuntimeException releaseFailure) { log.warn("Pet verification reservation release failed"); }
            }
            throw exception;
        }
        if (reservation != null) {
            try {
                if (!verificationRedisStore.finalize(rawVerificationToken, reservation.reservationId())) {
                    log.warn("Pet verification reservation finalize did not delete its token");
                }
            }
            catch (RuntimeException finalizeFailure) { log.warn("Pet verification reservation finalize failed"); }
        }
        if (!outcome.firstPetCandidate()) {
            return new PetCreationResult(
                    outcome.petId(),
                    ActivePetAssignmentStatus.NOT_APPLICABLE
            );
        }

        return new PetCreationResult(
                outcome.petId(),
                assignInitialActivePet(userId, outcome.petId())
        );
    }

    private PetCreationOutcome createPetWithPublicTagRetry(
            Long userId,
            PetCreateCommand command,
            itda.petverification.PetVerificationEvidence evidence
    ) {
        for (int attempt = 0; attempt < PUBLIC_TAG_SAVE_ATTEMPTS; attempt++) {
            String publicTag = petPublicTagGenerator.generate(command.nickname());
            try {
                if (evidence == null) {
                    return petCreationTransactionService.createAttempt(userId, command, publicTag);
                }
                return petCreationTransactionService.createAttempt(userId, command, publicTag, evidence);
            } catch (DataIntegrityViolationException exception) {
                if (!isPublicTagUniqueConstraintViolation(exception)) {
                    throw exception;
                }
            }
        }

        throw new BusinessException(ErrorCode.PET_PUBLIC_TAG_GENERATION_FAILED);
    }

    private ActivePetAssignmentStatus assignInitialActivePet(
            Long userId,
            Long petId
    ) {
        try {
            return activePetAssignmentTransactionService.assignIfAbsent(
                    userId,
                    petId
            );
        } catch (PessimisticLockingFailureException exception) {
            log.warn(
                    "Initial active pet assignment requires retry. "
                            + "userId={}, petId={}",
                    userId,
                    petId,
                    exception
            );
            return ActivePetAssignmentStatus.RETRY_REQUIRED;
        }
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
