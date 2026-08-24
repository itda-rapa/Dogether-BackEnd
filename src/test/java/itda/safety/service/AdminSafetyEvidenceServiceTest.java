package itda.safety.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.risk.contract.RiskSignalType;
import itda.risk.contract.RiskSourceType;
import itda.safety.domain.SafetyCaseStatus;
import itda.safety.domain.SafetyReviewCase;
import itda.safety.dto.SafetyEvidenceResponse.AccessStatus;
import itda.safety.dto.SafetyEvidenceResponse.SourceSummary;
import itda.safety.dto.SafetySignalResponse;
import itda.safety.repository.SafetyAdminQueryJdbcRepository;
import itda.safety.repository.SafetyReviewCaseJdbcRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminSafetyEvidenceServiceTest {

    private SafetyAdminQueryJdbcRepository queryRepository;
    private EvidenceAccessAuditWriter auditWriter;
    private AdminSafetyEvidenceService service;

    @BeforeEach
    void setUp() {
        AdminSafetyAuthorizationService authorization = mock(AdminSafetyAuthorizationService.class);
        SafetyReviewCaseJdbcRepository caseRepository = mock(SafetyReviewCaseJdbcRepository.class);
        queryRepository = mock(SafetyAdminQueryJdbcRepository.class);
        auditWriter = mock(EvidenceAccessAuditWriter.class);
        service = new AdminSafetyEvidenceService(
                authorization, caseRepository, queryRepository, auditWriter);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(safetyCase()));
        when(queryRepository.findEvidenceSignals(any(), any(), any(), anyInt()))
                .thenReturn(List.of(signal()));
    }

    @Test
    void successfulResolutionWritesSuccessAudit() {
        SourceSummary summary = new SourceSummary(
                "subject#TAG", "target#TAG", "ACTIVE", instant());
        when(queryRepository.findBlockEvidence(100L, 10L, 20L))
                .thenReturn(Optional.of(summary));

        var response = service.evidence(99L, 1L, "fact check", null, 20);

        assertThat(response.items()).singleElement()
                .satisfies(item -> assertThat(item.accessStatus()).isEqualTo(AccessStatus.AVAILABLE));
        verify(auditWriter).succeeded(1L, 99L, "USER_BLOCK", 100L, "fact check");
        verify(auditWriter).succeeded(1L, 99L, "EVIDENCE_PAGE", 1L, "fact check");
        verify(auditWriter, never()).failed(anyLong(), anyLong(), any(), anyLong(), any(), any());
    }

    @Test
    void missingSourceWritesFailedAuditAndReturnsSafeStatus() {
        when(queryRepository.findBlockEvidence(100L, 10L, 20L)).thenReturn(Optional.empty());

        var response = service.evidence(99L, 1L, "fact check", null, 20);

        assertThat(response.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.accessStatus()).isEqualTo(AccessStatus.SOURCE_NOT_FOUND);
                    assertThat(item.source()).isNull();
                });
        verify(auditWriter).failed(
                1L, 99L, "USER_BLOCK", 100L, "fact check", "SOURCE_NOT_FOUND");
        verify(auditWriter).succeeded(1L, 99L, "EVIDENCE_PAGE", 1L, "fact check");
    }

    @Test
    void resolverFailureWritesSanitizedFailureAuditAndRethrows() {
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(queryRepository.findBlockEvidence(100L, 10L, 20L)).thenThrow(failure);

        assertThatThrownBy(() -> service.evidence(99L, 1L, "fact check", null, 20))
                .isSameAs(failure);
        verify(auditWriter).failed(
                1L, 99L, "USER_BLOCK", 100L, "fact check", "SOURCE_LOOKUP_FAILED");
        verify(auditWriter).failed(
                1L, 99L, "EVIDENCE_PAGE", 1L, "fact check", "EVIDENCE_REQUEST_FAILED");
    }

    @Test
    void sourceAuditFailureIsFailClosedAndMarksRequestFailed() {
        when(queryRepository.findBlockEvidence(100L, 10L, 20L))
                .thenReturn(Optional.of(new SourceSummary(
                        "subject#TAG", "target#TAG", "ACTIVE", instant())));
        IllegalStateException auditFailure = new IllegalStateException("audit unavailable");
        doThrow(auditFailure).when(auditWriter)
                .succeeded(1L, 99L, "USER_BLOCK", 100L, "fact check");

        assertThatThrownBy(() -> service.evidence(99L, 1L, "fact check", null, 20))
                .isSameAs(auditFailure);
        verify(auditWriter).failed(
                1L, 99L, "EVIDENCE_PAGE", 1L, "fact check", "EVIDENCE_REQUEST_FAILED");
    }

    @Test
    void emptyEvidencePageStillWritesRequestSuccessAudit() {
        when(queryRepository.findEvidenceSignals(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        var response = service.evidence(99L, 1L, "fact check", null, 20);

        assertThat(response.items()).isEmpty();
        verify(auditWriter).succeeded(1L, 99L, "EVIDENCE_PAGE", 1L, "fact check");
    }

    @Test
    void evidenceQueryFailureWritesRequestFailureAudit() {
        IllegalStateException failure = new IllegalStateException("query unavailable");
        when(queryRepository.findEvidenceSignals(any(), any(), any(), anyInt()))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.evidence(99L, 1L, "fact check", null, 20))
                .isSameAs(failure);
        verify(auditWriter).failed(
                1L, 99L, "EVIDENCE_PAGE", 1L, "fact check", "EVIDENCE_REQUEST_FAILED");
    }

    @Test
    void requestSuccessAuditFailureIsFailClosedWithoutFalseFailureAudit() {
        when(queryRepository.findEvidenceSignals(any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        IllegalStateException failure = new IllegalStateException("audit unavailable");
        doThrow(failure).when(auditWriter)
                .succeeded(1L, 99L, "EVIDENCE_PAGE", 1L, "fact check");

        assertThatThrownBy(() -> service.evidence(99L, 1L, "fact check", null, 20))
                .isSameAs(failure);
        verify(auditWriter, never()).failed(anyLong(), anyLong(), any(), anyLong(), any(), any());
    }

    private SafetyReviewCase safetyCase() {
        return new SafetyReviewCase(
                1L, 10L, 20L, SafetyCaseStatus.OPEN, 30, 1, "USER_BLOCKED", 1,
                instant(), instant(), 50L, instant(), 0, instant(), instant());
    }

    private SafetySignalResponse signal() {
        return new SafetySignalResponse(
                50L, UUID.randomUUID(), RiskSourceType.USER_BLOCK, 100L,
                RiskSignalType.USER_BLOCKED, 30, 1, instant());
    }

    private Instant instant() {
        return Instant.parse("2026-08-24T04:00:00Z");
    }
}
