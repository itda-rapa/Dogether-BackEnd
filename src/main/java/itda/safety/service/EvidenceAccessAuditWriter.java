package itda.safety.service;

import itda.safety.domain.EvidenceAccessResult;
import itda.safety.repository.EvidenceAccessAuditJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EvidenceAccessAuditWriter {

    private final EvidenceAccessAuditJdbcRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeeded(
            long caseId, long adminUserId, String evidenceType, long resourceId, String purpose
    ) {
        repository.append(caseId, adminUserId, evidenceType, resourceId, purpose,
                EvidenceAccessResult.SUCCEEDED, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(
            long caseId,
            long adminUserId,
            String evidenceType,
            long resourceId,
            String purpose,
            String failureCode
    ) {
        repository.append(caseId, adminUserId, evidenceType, resourceId, purpose,
                EvidenceAccessResult.FAILED, failureCode);
    }
}
