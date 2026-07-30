package itda.friend.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class FriendRequestCommandServiceTest {

    @Mock
    private FriendRequestCommandTransactionService transactionService;

    private FriendRequestCommandService service;

    @BeforeEach
    void setUp() {
        service = new FriendRequestCommandService(transactionService);
    }

    @Test
    void mapsPendingPairConstraintToConcurrentConflict() {
        DataIntegrityViolationException failure =
                constraintFailure("uk_friend_request_pending_pair");
        given(transactionService.execute(1L, 2L)).willThrow(failure);

        assertBusinessError(
                () -> service.create(1L, 2L),
                ErrorCode.CONCURRENT_UPDATE_CONFLICT
        );
    }

    @Test
    void mapsFriendshipConstraintToAlreadyExists() {
        DataIntegrityViolationException failure =
                constraintFailure("uk_friendship_pair");
        given(transactionService.execute(1L, 2L)).willThrow(failure);

        assertBusinessError(
                () -> service.create(1L, 2L),
                ErrorCode.FRIENDSHIP_ALREADY_EXISTS
        );
    }

    @Test
    void rethrowsUnknownConstraintUnchanged() {
        DataIntegrityViolationException failure =
                constraintFailure("fk_unknown");
        given(transactionService.execute(1L, 2L)).willThrow(failure);

        assertThatThrownBy(() -> service.create(1L, 2L))
                .isSameAs(failure);
    }

    @Test
    void rethrowsFailureWhenConstraintNameIsNull() {
        DataIntegrityViolationException failure = constraintFailure(null);
        given(transactionService.execute(1L, 2L)).willThrow(failure);

        assertThatThrownBy(() -> service.create(1L, 2L))
                .isSameAs(failure);
    }

    private DataIntegrityViolationException constraintFailure(String name) {
        ConstraintViolationException hibernateFailure =
                mock(ConstraintViolationException.class);
        given(hibernateFailure.getConstraintName()).willReturn(name);
        return new DataIntegrityViolationException(
                "constraint failure",
                hibernateFailure
        );
    }

    private void assertBusinessError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            ErrorCode errorCode
    ) {
        assertThatThrownBy(operation)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }
}
