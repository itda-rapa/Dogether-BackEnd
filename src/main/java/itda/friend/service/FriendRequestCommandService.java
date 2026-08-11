package itda.friend.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.dto.response.FriendRequestResponse;
import itda.friend.service.FriendRequestActionResult.Accepted;
import itda.friend.service.FriendRequestActionResult.Rejected;
import itda.friend.service.FriendRequestActionResult.Terminal;
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
    private final FriendRequestActionTransactionService actionTransactionService;

    public FriendRequestCommandService(
            FriendRequestCommandTransactionService transactionService,
            FriendRequestActionTransactionService actionTransactionService
    ) {
        this.transactionService = transactionService;
        this.actionTransactionService = actionTransactionService;
    }

    @Transactional(propagation = Propagation.NEVER)
    public FriendRequestResponse accept(
            Long authenticatedUserId,
            Long requestId
    ) {
        try {
            FriendRequestActionResult result =
                    actionTransactionService.accept(
                            authenticatedUserId,
                            requestId
                    );
            if (result instanceof Accepted accepted) {
                return accepted.response();
            }
            if (result == Terminal.EXPIRED) {
                throw new BusinessException(
                        ErrorCode.FRIEND_REQUEST_NOT_PENDING
                );
            }
            throw new IllegalStateException(
                    "Unexpected friend request accept result"
            );
        } catch (DataIntegrityViolationException exception) {
            String constraintName = findConstraintName(exception);
            if (FRIENDSHIP_PAIR_CONSTRAINT.equalsIgnoreCase(constraintName)) {
                throw new BusinessException(
                        ErrorCode.FRIENDSHIP_ALREADY_EXISTS
                );
            }
            throw exception;
        }
    }

    @Transactional(propagation = Propagation.NEVER)
    public FriendRequestResponse reject(
            Long authenticatedUserId,
            Long requestId
    ) {
        FriendRequestActionResult result =
                actionTransactionService.reject(
                        authenticatedUserId,
                        requestId
                );
        if (result instanceof Rejected rejected) {
            return rejected.response();
        }
        if (result == Terminal.EXPIRED) {
            throw new BusinessException(
                    ErrorCode.FRIEND_REQUEST_NOT_PENDING
            );
        }
        throw new IllegalStateException(
                "Unexpected friend request reject result"
        );
    }

    @Transactional(propagation = Propagation.NEVER)
    public void cancel(Long authenticatedUserId, Long requestId) {
        FriendRequestActionResult result =
                actionTransactionService.cancel(
                        authenticatedUserId,
                        requestId
                );
        if (result == Terminal.CANCELED) {
            return;
        }
        if (result == Terminal.EXPIRED) {
            throw new BusinessException(
                    ErrorCode.FRIEND_REQUEST_NOT_PENDING
            );
        }
        throw new IllegalStateException(
                "Unexpected friend request cancel result"
        );
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
