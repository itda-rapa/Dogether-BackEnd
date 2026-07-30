package itda.friend.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FriendRequestCommandService {

    private static final String PENDING_PAIR_CONSTRAINT =
            "uk_friend_request_pending_pair";
    private static final String FRIENDSHIP_PAIR_CONSTRAINT =
            "uk_friendship_pair";

    private final FriendRequestCommandTransactionService transactionService;

    public FriendRequestCommandService(
            FriendRequestCommandTransactionService transactionService
    ) {
        this.transactionService = transactionService;
    }

    @Transactional(propagation = Propagation.NEVER)
    public FriendRequestCommandResult create(
            Long authenticatedUserId,
            Long targetPetId
    ) {
        try {
            return transactionService.execute(
                    authenticatedUserId,
                    targetPetId
            );
        } catch (DataIntegrityViolationException exception) {
            String constraintName = findConstraintName(exception);
            if (PENDING_PAIR_CONSTRAINT.equalsIgnoreCase(constraintName)) {
                throw new BusinessException(
                        ErrorCode.CONCURRENT_UPDATE_CONFLICT
                );
            }
            if (FRIENDSHIP_PAIR_CONSTRAINT.equalsIgnoreCase(constraintName)) {
                throw new BusinessException(
                        ErrorCode.FRIENDSHIP_ALREADY_EXISTS
                );
            }
            throw exception;
        }
    }

    private String findConstraintName(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof
                    org.hibernate.exception.ConstraintViolationException
                    constraintViolation) {
                return constraintViolation.getConstraintName();
            }
            current = current.getCause();
        }
        return null;
    }
}
