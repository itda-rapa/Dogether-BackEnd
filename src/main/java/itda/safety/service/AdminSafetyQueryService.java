package itda.safety.service;

import itda.chat.dto.response.CursorPage;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.risk.contract.RiskSignalType;
import itda.safety.domain.SafetyCaseStatus;
import itda.safety.domain.SafetyReviewCase;
import itda.safety.dto.SafetyCaseActionResponse;
import itda.safety.dto.SafetyCaseDetailResponse;
import itda.safety.dto.SafetyCasePageResponse;
import itda.safety.dto.SafetyCaseResponse;
import itda.safety.dto.SafetyUserResponse;
import itda.safety.dto.SafetySignalResponse;
import itda.safety.repository.SafetyAdminQueryJdbcRepository;
import itda.safety.repository.SafetyCaseActionJdbcRepository;
import itda.safety.repository.SafetyReviewCaseJdbcRepository;
import itda.safety.support.SafetyCursorCodec;
import itda.safety.support.SafetyCursorCodec.Cursor;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminSafetyQueryService {

    private static final int MAX_SIZE = 100;
    private static final int RECENT_SIGNAL_LIMIT = 100;

    private final AdminSafetyAuthorizationService authorization;
    private final SafetyReviewCaseJdbcRepository caseRepository;
    private final SafetyCaseActionJdbcRepository actionRepository;
    private final SafetyAdminQueryJdbcRepository queryRepository;

    @Transactional(readOnly = true)
    public SafetyCasePageResponse list(
            long adminUserId,
            SafetyCaseStatus status,
            RiskSignalType signalType,
            Long subjectUserId,
            Long targetUserId,
            Instant fromInclusive,
            Instant toExclusive,
            String cursor,
            int size
    ) {
        authorization.requireActiveAdmin(adminUserId);
        validateFilters(subjectUserId, targetUserId, fromInclusive, toExclusive, size);
        Cursor decoded = SafetyCursorCodec.decode(cursor);
        List<SafetyReviewCase> rows = queryRepository.findCases(
                status, signalType, subjectUserId, targetUserId, fromInclusive, toExclusive,
                decoded == null ? null : decoded.sortAt(),
                decoded == null ? null : decoded.id(), size + 1);
        boolean hasNext = rows.size() > size;
        if (hasNext) {
            rows = rows.subList(0, size);
        }
        Map<Long, String> publicTags = findPublicTags(rows);
        List<SafetyCaseResponse> items = rows.stream()
                .map(row -> response(row, publicTags))
                .toList();
        String nextCursor = hasNext && !rows.isEmpty()
                ? SafetyCursorCodec.encode(rows.getLast().lastDetectedAt(), rows.getLast().id())
                : null;
        return new SafetyCasePageResponse(items, CursorPage.of(nextCursor, hasNext));
    }

    @Transactional(readOnly = true)
    public SafetyCaseDetailResponse detail(long adminUserId, long caseId) {
        authorization.requireActiveAdmin(adminUserId);
        SafetyReviewCase safetyCase = findCase(caseId);
        Map<Long, String> publicTags = queryRepository.findPublicTags(userIds(safetyCase));
        List<SafetySignalResponse> signals =
                queryRepository.findSignals(safetyCase, RECENT_SIGNAL_LIMIT + 1);
        boolean hasMoreSignals = signals.size() > RECENT_SIGNAL_LIMIT;
        if (hasMoreSignals) {
            signals = signals.subList(0, RECENT_SIGNAL_LIMIT);
        }
        return new SafetyCaseDetailResponse(
                response(safetyCase, publicTags),
                List.copyOf(signals),
                hasMoreSignals,
                actionRepository.findByCaseId(caseId).stream()
                        .map(SafetyCaseActionResponse::from)
                        .toList());
    }

    private SafetyReviewCase findCase(long caseId) {
        if (caseId <= 0) {
            throw new BusinessException(ErrorCode.SAFETY_CASE_NOT_FOUND);
        }
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SAFETY_CASE_NOT_FOUND));
    }

    private Map<Long, String> findPublicTags(List<SafetyReviewCase> rows) {
        Set<Long> ids = new HashSet<>();
        rows.forEach(row -> ids.addAll(userIds(row)));
        return queryRepository.findPublicTags(ids);
    }

    private Set<Long> userIds(SafetyReviewCase row) {
        Set<Long> ids = new HashSet<>();
        ids.add(row.subjectUserId());
        if (row.targetUserId() != null) {
            ids.add(row.targetUserId());
        }
        return ids;
    }

    private SafetyCaseResponse response(SafetyReviewCase row, Map<Long, String> tags) {
        SafetyUserResponse subject = new SafetyUserResponse(
                row.subjectUserId(), tags.get(row.subjectUserId()));
        SafetyUserResponse target = row.targetUserId() == null ? null
                : new SafetyUserResponse(row.targetUserId(), tags.get(row.targetUserId()));
        return SafetyCaseResponse.from(row, subject, target);
    }

    private static void validateFilters(
            Long subjectUserId,
            Long targetUserId,
            Instant fromInclusive,
            Instant toExclusive,
            int size
    ) {
        if (size < 1 || size > MAX_SIZE
                || subjectUserId != null && subjectUserId <= 0
                || targetUserId != null && targetUserId <= 0
                || fromInclusive != null && toExclusive != null
                && !fromInclusive.isBefore(toExclusive)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
