package itda.petverification.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.petverification.PetVerificationFlowType;
import itda.petverification.PetVerificationRedisStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ExistingPetVerificationService {
    private final PetVerificationRedisStore redisStore;
    private final PetVerificationApplyTransactionService transactionService;

    public ExistingPetVerificationService(PetVerificationRedisStore redisStore,
                                          PetVerificationApplyTransactionService transactionService) {
        this.redisStore = redisStore;
        this.transactionService = transactionService;
    }

    public void apply(Long userId, Long petId, String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        PetVerificationRedisStore.Reservation reservation = redisStore.reserve(rawToken, userId,
                PetVerificationFlowType.EXISTING_PET_VERIFY, petId);
        try {
            transactionService.apply(userId, petId, reservation.evidence());
        } catch (RuntimeException exception) {
            try { redisStore.release(rawToken, reservation.reservationId()); }
            catch (RuntimeException releaseFailure) { log.warn("Pet verification reservation release failed"); }
            throw exception;
        }
        try {
            if (!redisStore.finalize(rawToken, reservation.reservationId())) {
                log.warn("Pet verification reservation finalize did not delete its token");
            }
        }
        catch (RuntimeException finalizeFailure) { log.warn("Pet verification reservation finalize failed"); }
    }
}
