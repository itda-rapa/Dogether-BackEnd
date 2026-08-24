package itda.safety.domain;

import java.time.Instant;

public record EvidenceAccessAudit(
        long id,
        long caseId,
        long adminUserId,
        String evidenceType,
        Long resourceId,
        String purpose,
        EvidenceAccessResult accessResult,
        String failureCode,
        Instant accessedAt
) {
}
