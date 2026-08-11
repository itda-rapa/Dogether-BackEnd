package itda.friend.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.dto.response.FriendRequestResponse;
import itda.friend.service.FriendRequestActionResult.Accepted;
import itda.friend.service.FriendRequestActionResult.Rejected;
import itda.friend.service.FriendRequestActionResult.Terminal;
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
    @Mock
    private FriendRequestActionTransactionService actionTransactionService;

    private FriendRequestCommandService service;

    @BeforeEach
    void setUp() {
        service = new FriendRequestCommandService(
                transactionService,
                actionTransactionService
        );
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

    @Test
    void returnsAcceptedAndRejectedResponses() {
        FriendRequestResponse accepted = mock(FriendRequestResponse.class);
        FriendRequestResponse rejected = mock(FriendRequestResponse.class);
        given(actionTransactionService.accept(1L, 10L))
                .willReturn(new Accepted(accepted));
        given(actionTransactionService.reject(1L, 11L))
                .willReturn(new Rejected(rejected));

        org.assertj.core.api.Assertions.assertThat(service.accept(1L, 10L))
                .isSameAs(accepted);
        org.assertj.core.api.Assertions.assertThat(service.reject(1L, 11L))
                .isSameAs(rejected);
    }

    @Test
    void mapsCommittedExpiredOutcomeOutsideTransaction() {
        given(actionTransactionService.accept(1L, 10L))
                .willReturn(Terminal.EXPIRED);
        given(actionTransactionService.reject(1L, 11L))
                .willReturn(Terminal.EXPIRED);
        given(actionTransactionService.cancel(1L, 12L))
                .willReturn(Terminal.EXPIRED);

        assertBusinessError(
                () -> service.accept(1L, 10L),
                ErrorCode.FRIEND_REQUEST_NOT_PENDING
        );
        assertBusinessError(
                () -> service.reject(1L, 11L),
                ErrorCode.FRIEND_REQUEST_NOT_PENDING
        );
        assertBusinessError(
                () -> service.cancel(1L, 12L),
                ErrorCode.FRIEND_REQUEST_NOT_PENDING
        );
    }

    @Test
    void mapsAcceptFriendshipConstraintOnly() {
        DataIntegrityViolationException known =
                constraintFailure("uk_friendship_pair");
        DataIntegrityViolationException unknown =
                constraintFailure("uk_unknown");
        given(actionTransactionService.accept(1L, 10L))
                .willThrow(known);
        given(actionTransactionService.accept(1L, 11L))
                .willThrow(unknown);

        assertBusinessError(
                () -> service.accept(1L, 10L),
                ErrorCode.FRIENDSHIP_ALREADY_EXISTS
        );
        assertThatThrownBy(() -> service.accept(1L, 11L))
                .isSameAs(unknown);
    }

    @Test
    void completesCanceledOutcome() {
        given(actionTransactionService.cancel(1L, 10L))
                .willReturn(Terminal.CANCELED);

        service.cancel(1L, 10L);
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
