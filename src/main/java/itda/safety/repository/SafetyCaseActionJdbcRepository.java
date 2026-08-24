package itda.safety.repository;

import itda.safety.domain.SafetyActionType;
import itda.safety.domain.SafetyCaseAction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
@RequiredArgsConstructor
public class SafetyCaseActionJdbcRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SafetyCaseAction append(
            long caseId,
            long adminUserId,
            SafetyActionType actionType,
            String reason,
            Map<String, Object> beforeState,
            Map<String, Object> afterState
    ) {
        validate(caseId, adminUserId, reason);
        List<SafetyCaseAction> rows = jdbc.query("""
                insert into safety_case_actions (
                    case_id, admin_user_id, action_type, reason, before_state, after_state
                ) values (?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb))
                returning *
                """, rowMapper(), caseId, adminUserId, actionType.name(), reason.trim(),
                writeJson(beforeState), writeJson(afterState));
        return rows.getFirst();
    }

    public List<SafetyCaseAction> findByCaseId(long caseId) {
        return jdbc.query("""
                select * from safety_case_actions
                 where case_id = ?
                 order by created_at asc, id asc
                """, rowMapper(), caseId);
    }

    private RowMapper<SafetyCaseAction> rowMapper() {
        return this::map;
    }

    private SafetyCaseAction map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SafetyCaseAction(
                resultSet.getLong("id"), resultSet.getLong("case_id"),
                resultSet.getLong("admin_user_id"),
                SafetyActionType.valueOf(resultSet.getString("action_type")),
                resultSet.getString("reason"), readJson(resultSet.getString("before_state")),
                readJson(resultSet.getString("after_state")),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Safety action state cannot be serialized", exception);
        }
    }

    private Map<String, Object> readJson(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored safety action state is invalid", exception);
        }
    }

    private static void validate(long caseId, long adminUserId, String reason) {
        if (caseId <= 0 || adminUserId <= 0 || reason == null
                || reason.isBlank() || reason.trim().length() > 500) {
            throw new IllegalArgumentException("Safety action fields are invalid");
        }
    }
}
