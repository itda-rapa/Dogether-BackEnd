package itda.safety.repository;

import itda.risk.contract.RiskSignalType;
import itda.risk.contract.RiskSourceType;
import itda.safety.domain.SafetyCaseStatus;
import itda.safety.domain.SafetyReviewCase;
import itda.safety.dto.SafetyEvidenceResponse.SourceSummary;
import itda.safety.dto.SafetySignalResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SafetyAdminQueryJdbcRepository {

    private final JdbcTemplate jdbc;

    public List<SafetyReviewCase> findCases(
            SafetyCaseStatus status,
            RiskSignalType signalType,
            Long subjectUserId,
            Long targetUserId,
            Instant fromInclusive,
            Instant toExclusive,
            Instant cursorAt,
            Long cursorId,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("select c.* from safety_review_cases c where 1=1");
        List<Object> arguments = new ArrayList<>();
        if (status != null) {
            sql.append(" and c.status = ?");
            arguments.add(status.name());
        }
        if (subjectUserId != null) {
            sql.append(" and c.subject_user_id = ?");
            arguments.add(subjectUserId);
        }
        if (targetUserId != null) {
            sql.append(" and c.target_user_id = ?");
            arguments.add(targetUserId);
        }
        if (fromInclusive != null) {
            sql.append(" and c.last_detected_at >= ?");
            arguments.add(Timestamp.from(fromInclusive));
        }
        if (toExclusive != null) {
            sql.append(" and c.last_detected_at < ?");
            arguments.add(Timestamp.from(toExclusive));
        }
        if (signalType != null) {
            sql.append("""
                     and exists (
                         select 1 from risk_signal_events r
                          where r.actor_user_id = c.subject_user_id
                            and (c.target_user_id is null or r.target_user_id = c.target_user_id)
                            and r.occurred_at >= c.first_detected_at
                            and r.occurred_at <= c.last_detected_at
                            and r.id <= c.last_evaluated_event_id
                            and r.signal_type = ?
                     )
                    """);
            arguments.add(signalType.name());
        }
        if (cursorAt != null) {
            sql.append(" and (c.created_at < ? or (c.created_at = ? and c.id < ?))");
            arguments.add(Timestamp.from(cursorAt));
            arguments.add(Timestamp.from(cursorAt));
            arguments.add(cursorId);
        }
        sql.append(" order by c.created_at desc, c.id desc limit ?");
        arguments.add(limit);
        return jdbc.query(sql.toString(), SafetyAdminQueryJdbcRepository::mapCase,
                arguments.toArray());
    }

    public Map<Long, String> findPublicTags(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(userIds.size(), "?"));
        Map<Long, String> result = new HashMap<>();
        jdbc.query("select id, public_tag from users where id in (" + placeholders + ")",
                (resultSet, rowNumber) -> Map.entry(
                        resultSet.getLong("id"), resultSet.getString("public_tag")),
                userIds.toArray()).forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(result);
    }

    public List<SafetySignalResponse> findSignals(SafetyReviewCase safetyCase, int limit) {
        return jdbc.query("""
                select id, event_id, source_type, source_id, signal_type, score,
                       score_policy_version, occurred_at
                  from risk_signal_events
                 where actor_user_id = ?
                   and (cast(? as bigint) is null or target_user_id = ?)
                   and occurred_at >= ? and occurred_at <= ?
                   and id <= ?
                 order by occurred_at desc, id desc
                 limit ?
                """, SafetyAdminQueryJdbcRepository::mapSignal,
                safetyCase.subjectUserId(), safetyCase.targetUserId(), safetyCase.targetUserId(),
                Timestamp.from(safetyCase.firstDetectedAt()),
                Timestamp.from(safetyCase.lastDetectedAt()),
                safetyCase.lastEvaluatedEventId(), limit);
    }

    public List<SafetySignalResponse> findEvidenceSignals(
            SafetyReviewCase safetyCase,
            Instant cursorAt,
            Long cursorId,
            int limit
    ) {
        return jdbc.query("""
                select id, event_id, source_type, source_id, signal_type, score,
                       score_policy_version, occurred_at
                  from risk_signal_events
                 where actor_user_id = ?
                   and (cast(? as bigint) is null or target_user_id = ?)
                   and occurred_at >= ? and occurred_at <= ?
                   and id <= ?
                   and (cast(? as timestamptz) is null or occurred_at < ?
                        or (occurred_at = ? and id < ?))
                 order by occurred_at desc, id desc
                 limit ?
                """, SafetyAdminQueryJdbcRepository::mapSignal,
                safetyCase.subjectUserId(), safetyCase.targetUserId(), safetyCase.targetUserId(),
                Timestamp.from(safetyCase.firstDetectedAt()),
                Timestamp.from(safetyCase.lastDetectedAt()),
                safetyCase.lastEvaluatedEventId(), nullableTimestamp(cursorAt),
                nullableTimestamp(cursorAt), nullableTimestamp(cursorAt), cursorId, limit);
    }

    public Optional<SourceSummary> findBlockEvidence(
            long sourceId, long subjectUserId, Long targetUserId
    ) {
        return jdbc.query("""
                select subject.public_tag as subject_public_tag,
                       target.public_tag as target_public_tag,
                       'ACTIVE' as source_status,
                       b.created_at as source_occurred_at
                  from user_blocks b
                  left join users subject on subject.id = ?
                  left join users target on target.id = ?
                 where b.id = ?
                   and b.blocked_user_id = ?
                   and (cast(? as bigint) is null or b.blocker_user_id = ?)
                """, SafetyAdminQueryJdbcRepository::mapSourceSummary,
                subjectUserId, targetUserId, sourceId, subjectUserId, targetUserId, targetUserId)
                .stream().findFirst();
    }

    public Optional<SourceSummary> findGreetingEvidence(
            long sourceId, long subjectUserId, Long targetUserId
    ) {
        return jdbc.query("""
                select subject.public_tag as subject_public_tag,
                       target.public_tag as target_public_tag,
                       g.status as source_status,
                       g.created_at as source_occurred_at
                  from greetings g
                  left join users subject on subject.id = ?
                  left join users target on target.id = ?
                 where g.id = ?
                   and exists (
                       select 1 from pets p
                        where p.id in (g.from_pet_id, g.to_pet_id)
                          and p.owner_user_id = ?
                   )
                   and (cast(? as bigint) is null or exists (
                       select 1 from pets p
                        where p.id in (g.from_pet_id, g.to_pet_id)
                          and p.owner_user_id = ?
                   ))
                """, SafetyAdminQueryJdbcRepository::mapSourceSummary,
                subjectUserId, targetUserId, sourceId, subjectUserId, targetUserId, targetUserId)
                .stream().findFirst();
    }

    private static SafetyReviewCase mapCase(ResultSet resultSet, int rowNumber) throws SQLException {
        long targetUserId = resultSet.getLong("target_user_id");
        return new SafetyReviewCase(
                resultSet.getLong("id"), resultSet.getLong("subject_user_id"),
                resultSet.wasNull() ? null : targetUserId,
                SafetyCaseStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("total_score"), resultSet.getLong("signal_count"),
                resultSet.getString("primary_signal_type"),
                resultSet.getInt("evaluation_policy_version"),
                resultSet.getTimestamp("first_detected_at").toInstant(),
                resultSet.getTimestamp("last_detected_at").toInstant(),
                resultSet.getLong("last_evaluated_event_id"),
                resultSet.getTimestamp("evaluated_at").toInstant(), resultSet.getLong("version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static SafetySignalResponse mapSignal(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new SafetySignalResponse(
                resultSet.getLong("id"), resultSet.getObject("event_id", java.util.UUID.class),
                RiskSourceType.valueOf(resultSet.getString("source_type")),
                resultSet.getLong("source_id"),
                RiskSignalType.valueOf(resultSet.getString("signal_type")),
                resultSet.getInt("score"), resultSet.getInt("score_policy_version"),
                resultSet.getTimestamp("occurred_at").toInstant());
    }

    private static SourceSummary mapSourceSummary(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new SourceSummary(
                resultSet.getString("subject_public_tag"),
                resultSet.getString("target_public_tag"),
                resultSet.getString("source_status"),
                resultSet.getTimestamp("source_occurred_at").toInstant());
    }

    private static Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
