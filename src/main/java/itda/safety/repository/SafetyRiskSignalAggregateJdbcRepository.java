package itda.safety.repository;

import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SafetyRiskSignalAggregateJdbcRepository {
    private final JdbcTemplate jdbc;

    public Aggregate aggregate(
            long subjectUserId, long targetUserId, Instant fromInclusive, Instant toExclusive
    ) {
        return jdbc.queryForObject("""
                with matching as (
                    select id, signal_type, score, occurred_at
                      from risk_signal_events
                     where actor_user_id = ? and target_user_id = ?
                       and occurred_at >= ? and occurred_at <= ?
                ), primary_signal as (
                    select signal_type
                      from matching
                     group by signal_type
                     order by sum(score) desc, count(*) desc, signal_type asc
                     limit 1
                )
                select count(*) as signal_count,
                       coalesce(sum(score), 0) as total_score,
                       min(occurred_at) as first_detected_at,
                       max(occurred_at) as last_detected_at,
                       max(id) as last_evaluated_event_id,
                       (select signal_type from primary_signal) as primary_signal_type
                  from matching
                """, (rs, rowNumber) -> new Aggregate(
                        rs.getLong("signal_count"), rs.getLong("total_score"),
                        rs.getTimestamp("first_detected_at") == null
                                ? null : rs.getTimestamp("first_detected_at").toInstant(),
                        rs.getTimestamp("last_detected_at") == null
                                ? null : rs.getTimestamp("last_detected_at").toInstant(),
                        rs.getLong("last_evaluated_event_id"),
                        rs.getString("primary_signal_type")),
                subjectUserId, targetUserId, Timestamp.from(fromInclusive), Timestamp.from(toExclusive));
    }

    public record Aggregate(
            long signalCount,
            long totalScore,
            Instant firstDetectedAt,
            Instant lastDetectedAt,
            long lastEvaluatedEventId,
            String primarySignalType
    ) { }
}
