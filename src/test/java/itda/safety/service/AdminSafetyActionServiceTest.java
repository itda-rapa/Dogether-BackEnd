package itda.safety.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.safety.domain.SafetyActionType;
import itda.safety.domain.SafetyCaseStatus;
import itda.safety.domain.SafetyReviewCase;
import itda.safety.dto.SafetyCaseActionRequest;
import itda.safety.repository.SafetyAdminQueryJdbcRepository;
import itda.safety.repository.SafetyCaseActionJdbcRepository;
import itda.safety.repository.SafetyReviewCaseJdbcRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AdminSafetyActionServiceTest {

    private AdminSafetyAuthorizationService authorization;
    private SafetyReviewCaseJdbcRepository caseRepository;
    private SafetyCaseActionJdbcRepository actionRepository;
    private SafetyAdminQueryJdbcRepository queryRepository;
    private AdminSafetyActionService service;

    @BeforeEach
    void setUp() {
        authorization = mock(AdminSafetyAuthorizationService.class);
        caseRepository = mock(SafetyReviewCaseJdbcRepository.class);
        actionRepository = mock(SafetyCaseActionJdbcRepository.class);
        queryRepository = mock(SafetyAdminQueryJdbcRepository.class);
        service = new AdminSafetyActionService(
                authorization, caseRepository, actionRepository, queryRepository);
    }

    @Test
    void dismissedTransitionsAndAppendsActionInOrder() {
        SafetyReviewCase before = safetyCase(SafetyCaseStatus.OPEN, 3);
        SafetyReviewCase after = safetyCase(SafetyCaseStatus.DISMISSED, 4);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(before));
        when(caseRepository.transition(1L, 3L, SafetyCaseStatus.OPEN,
                SafetyCaseStatus.DISMISSED)).thenReturn(Optional.of(after));
        when(queryRepository.findPublicTags(any())).thenReturn(Map.of(10L, "subject#TAG"));

        var response = service.resolve(
                99L, 1L, new SafetyCaseActionRequest("DISMISSED", "false positive"));

        assertThat(response.status()).isEqualTo(SafetyCaseStatus.DISMISSED);
        InOrder order = inOrder(caseRepository, actionRepository);
        order.verify(caseRepository).transition(
                1L, 3L, SafetyCaseStatus.OPEN, SafetyCaseStatus.DISMISSED);
        order.verify(actionRepository).append(
                eq(1L), eq(99L), eq(SafetyActionType.DISMISSED), eq("false positive"),
                anyMap(), anyMap());
    }

    @Test
    void invalidActionUsesDedicatedError() {
        assertError(
                () -> service.resolve(99L, 1L,
                        new SafetyCaseActionRequest("SUSPEND", "not allowed")),
                ErrorCode.SAFETY_ACTION_INVALID);
        verify(caseRepository, never()).findById(anyLong());
    }

    @Test
    void closedCaseUsesDedicatedConflict() {
        when(caseRepository.findById(1L))
                .thenReturn(Optional.of(safetyCase(SafetyCaseStatus.WARNING_RECORDED, 4)));

        assertError(
                () -> service.resolve(99L, 1L,
                        new SafetyCaseActionRequest("DISMISSED", "again")),
                ErrorCode.SAFETY_CASE_ALREADY_CLOSED);
        verify(actionRepository, never()).append(anyLong(), anyLong(), any(), any(), anyMap(), anyMap());
    }

    @Test
    void transitionRaceClosedByAnotherAdminUsesDedicatedClosedConflict() {
        SafetyReviewCase before = safetyCase(SafetyCaseStatus.OPEN, 3);
        when(caseRepository.findById(1L))
                .thenReturn(Optional.of(before),
                        Optional.of(safetyCase(SafetyCaseStatus.DISMISSED, 4)));
        when(caseRepository.transition(anyLong(), anyLong(), any(), any()))
                .thenReturn(Optional.empty());

        assertError(
                () -> service.resolve(99L, 1L,
                        new SafetyCaseActionRequest("WARNING_RECORDED", "warning")),
                ErrorCode.SAFETY_CASE_ALREADY_CLOSED);
        verify(actionRepository, never()).append(anyLong(), anyLong(), any(), any(), anyMap(), anyMap());
    }

    @Test
    void transitionRaceWithOpenSnapshotRefreshUsesConcurrentUpdateConflict() {
        SafetyReviewCase before = safetyCase(SafetyCaseStatus.OPEN, 3);
        SafetyReviewCase refreshed = safetyCase(SafetyCaseStatus.OPEN, 4);
        when(caseRepository.findById(1L))
                .thenReturn(Optional.of(before), Optional.of(refreshed));
        when(caseRepository.transition(
                1L, 3L, SafetyCaseStatus.OPEN, SafetyCaseStatus.WARNING_RECORDED))
                .thenReturn(Optional.empty());

        assertError(
                () -> service.resolve(99L, 1L,
                        new SafetyCaseActionRequest("WARNING_RECORDED", "warning")),
                ErrorCode.CONCURRENT_UPDATE_CONFLICT);
        verify(actionRepository, never()).append(
                anyLong(), anyLong(), any(), any(), anyMap(), anyMap());
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
                             ErrorCode errorCode) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    private SafetyReviewCase safetyCase(SafetyCaseStatus status, long version) {
        Instant detectedAt = Instant.parse("2026-08-24T04:00:00Z");
        return new SafetyReviewCase(
                1L, 10L, null, status, 30, 1, "USER_BLOCKED", 1,
                detectedAt, detectedAt, 50L, detectedAt, version, detectedAt, detectedAt);
    }
}
