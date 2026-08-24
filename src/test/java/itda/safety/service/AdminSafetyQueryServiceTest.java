package itda.safety.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.risk.contract.RiskSignalType;
import itda.risk.contract.RiskSourceType;
import itda.safety.domain.SafetyCaseStatus;
import itda.safety.domain.SafetyReviewCase;
import itda.safety.dto.SafetySignalResponse;
import itda.safety.repository.SafetyAdminQueryJdbcRepository;
import itda.safety.repository.SafetyCaseActionJdbcRepository;
import itda.safety.repository.SafetyReviewCaseJdbcRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class AdminSafetyQueryServiceTest {

    @Test
    void detailFetchesLimitPlusOneAndReportsTruncation() {
        AdminSafetyAuthorizationService authorization = mock(AdminSafetyAuthorizationService.class);
        SafetyReviewCaseJdbcRepository caseRepository = mock(SafetyReviewCaseJdbcRepository.class);
        SafetyCaseActionJdbcRepository actionRepository = mock(SafetyCaseActionJdbcRepository.class);
        SafetyAdminQueryJdbcRepository queryRepository = mock(SafetyAdminQueryJdbcRepository.class);
        AdminSafetyQueryService service = new AdminSafetyQueryService(
                authorization, caseRepository, actionRepository, queryRepository);
        SafetyReviewCase safetyCase = safetyCase();
        when(caseRepository.findById(1L)).thenReturn(Optional.of(safetyCase));
        when(queryRepository.findPublicTags(any())).thenReturn(Map.of(10L, "subject#TAG"));
        when(queryRepository.findSignals(safetyCase, 101)).thenReturn(signals(101));
        when(actionRepository.findByCaseId(1L)).thenReturn(List.of());

        var response = service.detail(99L, 1L);

        assertThat(response.recentSignals()).hasSize(100);
        assertThat(response.hasMoreSignals()).isTrue();
        verify(queryRepository).findSignals(safetyCase, 101);
    }

    private List<SafetySignalResponse> signals(int size) {
        return LongStream.rangeClosed(1, size)
                .mapToObj(id -> new SafetySignalResponse(
                        id, UUID.randomUUID(), RiskSourceType.USER_BLOCK, id,
                        RiskSignalType.USER_BLOCKED, 30, 1, instant()))
                .toList();
    }

    private SafetyReviewCase safetyCase() {
        return new SafetyReviewCase(
                1L, 10L, null, SafetyCaseStatus.OPEN, 30, 1, "USER_BLOCKED", 1,
                instant(), instant(), 50L, instant(), 0, instant(), instant());
    }

    private Instant instant() {
        return Instant.parse("2026-08-24T04:00:00Z");
    }
}
