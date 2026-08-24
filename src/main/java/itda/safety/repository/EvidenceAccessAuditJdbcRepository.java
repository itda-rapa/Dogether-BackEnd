package itda.safety.repository;

import itda.safety.domain.EvidenceAccessAudit;
import itda.safety.domain.EvidenceAccessResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class EvidenceAccessAuditJdbcRepository {
    private static final RowMapper<EvidenceAccessAudit> ROW_MAPPER =
            EvidenceAccessAuditJdbcRepository::map;

    private final JdbcTemplate jdbc;

    public EvidenceAccessAudit append(
            long caseId,
            long adminUserId,
            String evidenceType,
            Long resourceId,
            String purpose,
            EvidenceAccessResult result,
            String failureCode
    ) {
        validate(caseId, adminUserId, evidenceType, resourceId, purpose, result, failureCode);
        return jdbc.query("""
                insert into evidence_access_audits (
                    case_id, admin_user_id, evidence_type, resource_id,
                    purpose, access_result, failure_code
                ) values (?, ?, ?, ?, ?, ?, ?)
                returning *
                """, ROW_MAPPER, caseId, adminUserId, evidenceType.trim(), resourceId, purpose.trim(),
                result.name(), normalize(failureCode)).getFirst();
    }

    public List<EvidenceAccessAudit> findByCaseId(long caseId) {
        return jdbc.query("""
                select * from evidence_access_audits
                 where case_id = ? order by accessed_at desc, id desc
                """, ROW_MAPPER, caseId);
    }

    private static EvidenceAccessAudit map(ResultSet resultSet, int rowNumber) throws SQLException {
        long resourceId = resultSet.getLong("resource_id");
        Long nullableResourceId = resultSet.wasNull() ? null : resourceId;
        return new EvidenceAccessAudit(
                resultSet.getLong("id"), resultSet.getLong("case_id"),
                resultSet.getLong("admin_user_id"), resultSet.getString("evidence_type"),
                nullableResourceId, resultSet.getString("purpose"),
                EvidenceAccessResult.valueOf(resultSet.getString("access_result")),
                resultSet.getString("failure_code"),
                resultSet.getTimestamp("accessed_at").toInstant());
    }

    private static void validate(
            long caseId, long adminUserId, String evidenceType, Long resourceId,
            String purpose, EvidenceAccessResult result, String failureCode
    ) {
        if (caseId <= 0 || adminUserId <= 0 || resourceId != null && resourceId <= 0
                || evidenceType == null || evidenceType.isBlank() || evidenceType.trim().length() > 50
                || purpose == null || purpose.isBlank() || purpose.trim().length() > 500) {
            throw new IllegalArgumentException("Evidence audit fields are invalid");
        }
        String normalized = normalize(failureCode);
        if (result == null
                || result == EvidenceAccessResult.SUCCEEDED && normalized != null
                || result == EvidenceAccessResult.FAILED && normalized == null) {
            throw new IllegalArgumentException("Evidence audit result is invalid");
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 50 || !normalized.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException("failureCode must be a sanitized code");
        }
        return normalized;
    }
}
