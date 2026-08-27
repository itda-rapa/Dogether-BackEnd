package itda.medicalsupport.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.medicalsupport.domain.MedicalSupportRevision;
import itda.medicalsupport.ingestion.MedicalSupportCandidate;
import itda.medicalsupport.ingestion.MedicalSupportSourceAdapter;
import itda.medicalsupport.ingestion.MedicalSupportSourceRegistry;
import itda.medicalsupport.ingestion.MedicalSupportCandidateValidator;
import itda.medicalsupport.ingestion.OfficialSourceExtractionException;
import java.sql.SQLException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MedicalSupportSourceIngestionTransactionService {
    private static final String STABLE_SEMANTIC_CONSTRAINT = "ux_medical_support_revision_stable_semantic";
    private static final String FALLBACK_SEMANTIC_CONSTRAINT = "ux_medical_support_revision_fallback_semantic";
    private static final String RAW_PARSER_CONSTRAINT = "ux_medical_support_revision_raw_parser";
    private final MedicalSupportSourceRegistry registry;
    private final MedicalSupportRevisionCreationService revisions;
    private final MedicalSupportAttemptService attempts;

    public MedicalSupportIngestionResult ingest(String sourceKey) {
        MedicalSupportSourceAdapter adapter = registry.find(sourceKey).orElseThrow(() -> new BusinessException(ErrorCode.MEDICAL_SUPPORT_SOURCE_NOT_FOUND));
        MedicalSupportCandidate candidate = null;
        try { candidate = adapter.collect(); MedicalSupportCandidateValidator.validate(candidate); }
        catch (RuntimeException exception) {
            String contentType = candidate != null ? candidate.contentType()
                    : exception instanceof OfficialSourceExtractionException extraction ? extraction.contentType() : null;
            String sourceHash = candidate != null ? candidate.sourceHash()
                    : exception instanceof OfficialSourceExtractionException extraction ? extraction.sourceHash() : null;
            String failureReason = failure(exception);
            attempts.failed(sourceKey, candidate != null ? candidate.sourceUrl() : adapter.sourceUrl(), failureReason, contentType, sourceHash);
            return MedicalSupportIngestionResult.failed(sourceKey, failureReason);
        }
        MedicalSupportRevisionCreationService.RevisionResolution resolution;
        try { resolution = revisions.createOrFind(candidate); }
        catch (DataIntegrityViolationException exception) {
            if (!isSemanticUniqueViolation(exception)) throw exception;
            resolution = isRawParserUniqueViolation(exception)
                    ? revisions.findByRawIdentity(candidate) : revisions.findBySemanticIdentity(candidate);
        }
        MedicalSupportRevision revision = resolution.revision();
        attempts.succeeded(sourceKey, adapter.sourceUrl(), candidate.contentType(), candidate.sourceHash(), revision);
        return MedicalSupportIngestionResult.succeeded(sourceKey, revision, resolution.created());
    }

    private boolean isSemanticUniqueViolation(DataIntegrityViolationException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                String constraint = constraintName(sql);
                return STABLE_SEMANTIC_CONSTRAINT.equals(constraint) || FALLBACK_SEMANTIC_CONSTRAINT.equals(constraint)
                        || RAW_PARSER_CONSTRAINT.equals(constraint);
            }
            current = current.getCause();
        }
        return false;
    }
    private boolean isRawParserUniqueViolation(DataIntegrityViolationException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sql && RAW_PARSER_CONSTRAINT.equals(constraintName(sql))) return true;
            current = current.getCause();
        }
        return false;
    }
    private String constraintName(SQLException exception) {
        try {
            Object detail = exception.getClass().getMethod("getServerErrorMessage").invoke(exception);
            return detail == null ? null : (String) detail.getClass().getMethod("getConstraint").invoke(detail);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
    private String failure(RuntimeException exception) { String message = exception.getMessage(); return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message.substring(0, Math.min(message.length(), 1000)); }
}
