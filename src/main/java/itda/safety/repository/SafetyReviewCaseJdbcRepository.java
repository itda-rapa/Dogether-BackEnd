package itda.safety.repository;

import itda.safety.domain.SafetyCaseSnapshot;
import itda.safety.domain.SafetyCaseStatus;
import itda.safety.domain.SafetyReviewCase;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class SafetyReviewCaseJdbcRepository {
    private static final RowMapper<SafetyReviewCase> ROW_MAPPER = SafetyReviewCaseJdbcRepository::map;

    private final JdbcTemplate jdbc;

    /**
     * Creates or refreshes the open case for a subject/target pair.
     *
     * <p>An empty result means a terminal case already covers this snapshot. A snapshot newer than
     * the terminal case may open a new review case. The transaction-scoped advisory lock prevents
     * a close and a same-pair open from making the closed-snapshot check race-prone.</p>
     */
    @Transactional
    public Optional<SafetyReviewCase> upsertOpenCase(
            long subjectUserId, Long targetUserId, SafetyCaseSnapshot snapshot
    ) {
        if (subjectUserId <= 0 || targetUserId != null && targetUserId <= 0) {
            throw new IllegalArgumentException("Safety case user id is invalid");
        }
        List<SafetyReviewCase> rows = jdbc.query("""
                with pair_lock as (
                    select pg_advisory_xact_lock(hashtextextended(
                        concat(cast(? as text), ':', coalesce(cast(? as text), 'null')), 0))
                ), upserted as (
                    insert into safety_review_cases (
                        subject_user_id, target_user_id, status, total_score, signal_count,
                        primary_signal_type, score_policy_version, first_detected_at,
                        last_detected_at, last_evaluated_event_id, evaluated_at
                    )
                    select ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, ?
                      from pair_lock
                     where not exists (
                        select 1
                          from safety_review_cases covered
                         where covered.subject_user_id = ?
                           and covered.target_user_id is not distinct from ?
                           and covered.status in ('DISMISSED', 'WARNING_RECORDED')
                           and covered.last_evaluated_event_id >= ?
                     )
                    on conflict (subject_user_id, target_user_id)
                        where status in ('OPEN', 'REVIEWING')
                    do update set
                        total_score = excluded.total_score,
                        signal_count = excluded.signal_count,
                        primary_signal_type = excluded.primary_signal_type,
                        score_policy_version = excluded.score_policy_version,
                        first_detected_at = excluded.first_detected_at,
                        last_detected_at = excluded.last_detected_at,
                        last_evaluated_event_id = excluded.last_evaluated_event_id,
                        evaluated_at = excluded.evaluated_at,
                        version = safety_review_cases.version + 1,
                        updated_at = current_timestamp
                    where excluded.evaluated_at >= safety_review_cases.evaluated_at
                      and excluded.last_evaluated_event_id >= safety_review_cases.last_evaluated_event_id
                    returning *
                )
                select * from upserted
                union all
                select current_case.*
                  from safety_review_cases current_case
                 where current_case.subject_user_id = ?
                   and current_case.target_user_id is not distinct from ?
                   and current_case.status in ('OPEN', 'REVIEWING')
                   and not exists (select 1 from upserted)
                 order by last_detected_at desc, id desc
                 limit 1
                """, ROW_MAPPER,
                subjectUserId, targetUserId,
                subjectUserId, targetUserId,
                snapshot.totalScore(), snapshot.signalCount(), snapshot.primarySignalType(),
                snapshot.scorePolicyVersion(), Timestamp.from(snapshot.firstDetectedAt()),
                Timestamp.from(snapshot.lastDetectedAt()), snapshot.lastEvaluatedEventId(),
                Timestamp.from(snapshot.evaluatedAt()),
                subjectUserId, targetUserId, snapshot.lastEvaluatedEventId(),
                subjectUserId, targetUserId);
        return rows.stream().findFirst();
    }

    public Optional<SafetyReviewCase> findById(long caseId) {
        return jdbc.query("select * from safety_review_cases where id = ?", ROW_MAPPER, caseId)
                .stream().findFirst();
    }

    public Optional<SafetyReviewCase> transition(
            long caseId,
            long expectedVersion,
            SafetyCaseStatus expectedStatus,
            SafetyCaseStatus nextStatus
    ) {
        if (!isAllowedTransition(expectedStatus, nextStatus)) {
            throw new IllegalArgumentException("Safety case transition is invalid");
        }
        return jdbc.query("""
                update safety_review_cases
                   set status = ?, version = version + 1, updated_at = current_timestamp
                 where id = ? and version = ? and status = ?
                returning *
                """, ROW_MAPPER, nextStatus.name(), caseId, expectedVersion, expectedStatus.name())
                .stream().findFirst();
    }

    private static boolean isAllowedTransition(SafetyCaseStatus from, SafetyCaseStatus to) {
        if (from == SafetyCaseStatus.OPEN && to == SafetyCaseStatus.REVIEWING) {
            return true;
        }
        return from.isOpen()
                && (to == SafetyCaseStatus.DISMISSED || to == SafetyCaseStatus.WARNING_RECORDED);
    }

    private static SafetyReviewCase map(ResultSet resultSet, int rowNumber) throws SQLException {
        long targetUserId = resultSet.getLong("target_user_id");
        Long nullableTargetUserId = resultSet.wasNull() ? null : targetUserId;
        return new SafetyReviewCase(
                resultSet.getLong("id"),
                resultSet.getLong("subject_user_id"),
                nullableTargetUserId,
                SafetyCaseStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("total_score"),
                resultSet.getLong("signal_count"),
                resultSet.getString("primary_signal_type"),
                resultSet.getInt("score_policy_version"),
                resultSet.getTimestamp("first_detected_at").toInstant(),
                resultSet.getTimestamp("last_detected_at").toInstant(),
                resultSet.getLong("last_evaluated_event_id"),
                resultSet.getTimestamp("evaluated_at").toInstant(),
                resultSet.getLong("version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }
}
