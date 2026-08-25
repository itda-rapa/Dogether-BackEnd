package itda.safety.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.safety.domain.SafetyActionType;
import itda.safety.domain.SafetyReviewCase;
import itda.safety.dto.SafetyCaseActionRequest;
import itda.safety.dto.SafetyCaseResponse;
import itda.safety.dto.SafetyUserResponse;
import itda.safety.repository.SafetyAdminQueryJdbcRepository;
import itda.safety.repository.SafetyCaseActionJdbcRepository;
import itda.safety.repository.SafetyReviewCaseJdbcRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminSafetyActionService {

    private final AdminSafetyAuthorizationService authorization;
    private final SafetyReviewCaseJdbcRepository caseRepository;
    private final SafetyCaseActionJdbcRepository actionRepository;
    private final SafetyAdminQueryJdbcRepository queryRepository;

    @Transactional
    public SafetyCaseResponse resolve(
            long adminUserId,
            long caseId,
            SafetyCaseActionRequest request
    ) {
        authorization.requireActiveAdmin(adminUserId);
        SafetyActionType actionType = parseAction(request.actionType());
        SafetyReviewCase before = caseRepository.findById(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SAFETY_CASE_NOT_FOUND));
        if (!before.status().isOpen()) {
            throw new BusinessException(ErrorCode.SAFETY_CASE_ALREADY_CLOSED);
        }
        SafetyReviewCase after = caseRepository.transition(
                        caseId, before.version(), before.status(),
                        actionType == SafetyActionType.DISMISSED
                                ? itda.safety.domain.SafetyCaseStatus.DISMISSED
                                : itda.safety.domain.SafetyCaseStatus.WARNING_RECORDED)
                .orElseThrow(() -> concurrentResult(caseId));
        actionRepository.append(
                caseId, adminUserId, actionType, request.reason(), state(before), state(after));
        Map<Long, String> tags = queryRepository.findPublicTags(
                after.targetUserId() == null
                        ? java.util.List.of(after.subjectUserId())
                        : java.util.List.of(after.subjectUserId(), after.targetUserId()));
        return SafetyCaseResponse.from(
                after,
                new SafetyUserResponse(after.subjectUserId(), tags.get(after.subjectUserId())),
                after.targetUserId() == null ? null
                        : new SafetyUserResponse(after.targetUserId(), tags.get(after.targetUserId())));
    }

    private RuntimeException concurrentResult(long caseId) {
        var latest = caseRepository.findById(caseId);
        if (latest.isEmpty()) {
            return new BusinessException(ErrorCode.SAFETY_CASE_NOT_FOUND);
        }
        if (!latest.orElseThrow().status().isOpen()) {
            return new BusinessException(ErrorCode.SAFETY_CASE_ALREADY_CLOSED);
        }
        return new BusinessException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
    }

    private SafetyActionType parseAction(String raw) {
        try {
            return SafetyActionType.valueOf(raw.trim());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.SAFETY_ACTION_INVALID);
        }
    }

    private Map<String, Object> state(SafetyReviewCase value) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", value.status().name());
        state.put("totalScore", value.totalScore());
        state.put("signalCount", value.signalCount());
        state.put("version", value.version());
        state.put("updatedAt", value.updatedAt());
        return state;
    }
}
