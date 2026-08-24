package itda.safety.service;

import itda.chat.dto.response.CursorPage;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.risk.contract.RiskSourceType;
import itda.safety.domain.SafetyReviewCase;
import itda.safety.dto.SafetyEvidencePageResponse;
import itda.safety.dto.SafetyEvidenceResponse;
import itda.safety.dto.SafetyEvidenceResponse.AccessStatus;
import itda.safety.dto.SafetyEvidenceResponse.SourceSummary;
import itda.safety.dto.SafetySignalResponse;
import itda.safety.repository.SafetyAdminQueryJdbcRepository;
import itda.safety.repository.SafetyReviewCaseJdbcRepository;
import itda.safety.support.SafetyCursorCodec;
import itda.safety.support.SafetyCursorCodec.Cursor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminSafetyEvidenceService {

    private static final int MAX_SIZE = 100;
    private static final String PAGE_EVIDENCE_TYPE = "EVIDENCE_PAGE";
    private static final String REQUEST_FAILURE_CODE = "EVIDENCE_REQUEST_FAILED";

    private final AdminSafetyAuthorizationService authorization;
    private final SafetyReviewCaseJdbcRepository caseRepository;
    private final SafetyAdminQueryJdbcRepository queryRepository;
    private final EvidenceAccessAuditWriter auditWriter;

    public SafetyEvidencePageResponse evidence(
            long adminUserId,
            long caseId,
            String purpose,
            String cursor,
            int size
    ) {
        authorization.requireActiveAdmin(adminUserId);
        validate(purpose, size);
        SafetyReviewCase safetyCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SAFETY_CASE_NOT_FOUND));
        String normalizedPurpose = purpose.trim();
        List<SafetySignalResponse> signals;
        List<SafetyEvidenceResponse> items = new ArrayList<>();
        boolean hasNext;
        try {
            Cursor decoded = SafetyCursorCodec.decode(cursor);
            signals = queryRepository.findEvidenceSignals(
                    safetyCase,
                    decoded == null ? null : decoded.sortAt(),
                    decoded == null ? null : decoded.id(),
                    size + 1);
            hasNext = signals.size() > size;
            if (hasNext) {
                signals = signals.subList(0, size);
            }
            for (SafetySignalResponse signal : signals) {
                items.add(resolve(adminUserId, safetyCase, normalizedPurpose, signal));
            }
        } catch (RuntimeException exception) {
            auditWriter.failed(safetyCase.id(), adminUserId, PAGE_EVIDENCE_TYPE,
                    safetyCase.id(), normalizedPurpose, REQUEST_FAILURE_CODE);
            throw exception;
        }
        auditWriter.succeeded(safetyCase.id(), adminUserId, PAGE_EVIDENCE_TYPE,
                safetyCase.id(), normalizedPurpose);
        String nextCursor = hasNext && !signals.isEmpty()
                ? SafetyCursorCodec.encode(signals.getLast().occurredAt(), signals.getLast().signalId())
                : null;
        return new SafetyEvidencePageResponse(List.copyOf(items), CursorPage.of(nextCursor, hasNext));
    }

    private SafetyEvidenceResponse resolve(
            long adminUserId,
            SafetyReviewCase safetyCase,
            String purpose,
            SafetySignalResponse signal
    ) {
        String evidenceType = signal.sourceType().name();
        Optional<SourceSummary> source;
        try {
            source = findSource(signal, safetyCase);
        } catch (RuntimeException exception) {
            auditWriter.failed(safetyCase.id(), adminUserId, evidenceType, signal.sourceId(),
                    purpose, "SOURCE_LOOKUP_FAILED");
            throw exception;
        }
        if (source.isPresent()) {
            auditWriter.succeeded(
                    safetyCase.id(), adminUserId, evidenceType, signal.sourceId(), purpose);
            return response(signal, AccessStatus.AVAILABLE, source.get());
        }
        auditWriter.failed(safetyCase.id(), adminUserId, evidenceType, signal.sourceId(),
                purpose, "SOURCE_NOT_FOUND");
        return response(signal, AccessStatus.SOURCE_NOT_FOUND, null);
    }

    private Optional<SourceSummary> findSource(
            SafetySignalResponse signal, SafetyReviewCase safetyCase
    ) {
        if (signal.sourceType() == RiskSourceType.USER_BLOCK) {
            return queryRepository.findBlockEvidence(
                    signal.sourceId(), safetyCase.subjectUserId(), safetyCase.targetUserId());
        }
        if (signal.sourceType() == RiskSourceType.GREETING) {
            return queryRepository.findGreetingEvidence(
                    signal.sourceId(), safetyCase.subjectUserId(), safetyCase.targetUserId());
        }
        return Optional.empty();
    }

    private SafetyEvidenceResponse response(
            SafetySignalResponse signal, AccessStatus status, SourceSummary source
    ) {
        return new SafetyEvidenceResponse(
                signal.signalId(), signal.signalType(), signal.sourceType(), signal.sourceId(),
                signal.occurredAt(), status, source);
    }

    private static void validate(String purpose, int size) {
        if (purpose == null || purpose.isBlank() || purpose.trim().length() > 500
                || size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
