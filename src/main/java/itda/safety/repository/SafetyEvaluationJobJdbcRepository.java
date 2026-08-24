package itda.safety.repository;

import itda.safety.domain.SafetyEvaluationJob;
import itda.safety.domain.SafetyEvaluationJobStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class SafetyEvaluationJobJdbcRepository {
    private final JdbcTemplate jdbc;

    public boolean enqueueByEventId(UUID eventId) {
        return jdbc.update("""
                insert into safety_case_evaluation_jobs (risk_signal_event_id, available_at)
                select id, greatest(current_timestamp, occurred_at)
                  from risk_signal_events
                 where event_id = ?
                on conflict (risk_signal_event_id) do nothing
                """, eventId) == 1;
    }

    public int reconcileMissing(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("reconcile limit must be positive");
        }
        return jdbc.update("""
                insert into safety_case_evaluation_jobs (risk_signal_event_id, available_at)
                select missing.id, greatest(current_timestamp, missing.occurred_at)
                  from (
                        select event.id, event.occurred_at
                          from risk_signal_events event
                          left join safety_case_evaluation_jobs job
                            on job.risk_signal_event_id = event.id
                         where job.id is null
                         order by event.id
                         limit ?
                  ) missing
                on conflict (risk_signal_event_id) do nothing
                """, limit);
    }

    @Transactional
    public Optional<ClaimedEvaluation> claimOne(
            String workerId, Instant now, Duration lease, int maxAttempts
    ) {
        UUID claimToken = UUID.randomUUID();
        List<ClaimedEvaluation> claimed = jdbc.query("""
                with candidate as (
                    select job.id
                      from safety_case_evaluation_jobs job
                     where (
                            (job.status = 'PENDING' and job.available_at <= ? and job.attempts < ?)
                            or (job.status = 'PROCESSING' and job.claimed_at < ?)
                     )
                     order by job.available_at, job.id
                     for update skip locked
                     limit 1
                ), updated as (
                    update safety_case_evaluation_jobs job
                       set status = 'PROCESSING',
                           attempts = case
                               when job.status = 'PROCESSING' and job.attempts >= ?
                                   then job.attempts
                               else job.attempts + 1
                           end,
                           claimed_at = ?, worker_id = ?, claim_token = ?,
                           last_error_code = null, updated_at = current_timestamp
                      from candidate
                     where job.id = candidate.id
                    returning job.*
                )
                select updated.*, event.actor_user_id, event.target_user_id, event.occurred_at
                  from updated
                  join risk_signal_events event on event.id = updated.risk_signal_event_id
                """, this::mapClaimed,
                Timestamp.from(now), maxAttempts, Timestamp.from(now.minus(lease)),
                maxAttempts, Timestamp.from(now), workerId, claimToken);
        return claimed.stream().findFirst();
    }

    public boolean complete(ClaimedEvaluation claimed) {
        return jdbc.update("""
                update safety_case_evaluation_jobs
                   set status = 'COMPLETED', claimed_at = null, worker_id = null,
                       claim_token = null, last_error_code = null, updated_at = current_timestamp
                 where id = ? and status = 'PROCESSING' and claim_token = ?
                """, claimed.job().id(), claimed.job().claimToken()) == 1;
    }

    public boolean failTerminal(ClaimedEvaluation claimed, String errorCode) {
        String sanitizedErrorCode = normalizeErrorCode(errorCode);
        return jdbc.update("""
                update safety_case_evaluation_jobs
                   set status = 'FAILED', claimed_at = null, worker_id = null,
                       claim_token = null, last_error_code = ?, updated_at = current_timestamp
                 where id = ? and status = 'PROCESSING' and claim_token = ?
                """, sanitizedErrorCode, claimed.job().id(), claimed.job().claimToken()) == 1;
    }

    public boolean retry(ClaimedEvaluation claimed, Instant availableAt, String errorCode) {
        String sanitizedErrorCode = normalizeErrorCode(errorCode);
        return jdbc.update("""
                update safety_case_evaluation_jobs
                   set status = 'PENDING', available_at = ?, claimed_at = null,
                       worker_id = null, claim_token = null, last_error_code = ?,
                       updated_at = current_timestamp
                 where id = ? and status = 'PROCESSING' and claim_token = ?
                """, Timestamp.from(availableAt), sanitizedErrorCode,
                claimed.job().id(), claimed.job().claimToken()) == 1;
    }

    public boolean deferUntil(ClaimedEvaluation claimed, Instant availableAt) {
        return jdbc.update("""
                update safety_case_evaluation_jobs
                   set status = 'PENDING', attempts = greatest(attempts - 1, 0),
                       available_at = ?, claimed_at = null, worker_id = null,
                       claim_token = null, last_error_code = null, updated_at = current_timestamp
                 where id = ? and status = 'PROCESSING' and claim_token = ?
                """, Timestamp.from(availableAt),
                claimed.job().id(), claimed.job().claimToken()) == 1;
    }

    public boolean requeueFailed(long jobId, Instant availableAt) {
        return jdbc.update("""
                update safety_case_evaluation_jobs
                   set status = 'PENDING', attempts = 0, available_at = ?, last_error_code = null,
                       updated_at = current_timestamp
                 where id = ? and status = 'FAILED'
                """, Timestamp.from(availableAt), jobId) == 1;
    }

    public Optional<SafetyEvaluationJob> findByRiskSignalEventId(long riskSignalEventId) {
        return jdbc.query("""
                select * from safety_case_evaluation_jobs where risk_signal_event_id = ?
                """, this::mapJob, riskSignalEventId).stream().findFirst();
    }

    private ClaimedEvaluation mapClaimed(ResultSet rs, int rowNumber) throws SQLException {
        return new ClaimedEvaluation(new SafetyEvaluationJob(
                rs.getLong("id"), rs.getLong("risk_signal_event_id"),
                SafetyEvaluationJobStatus.valueOf(rs.getString("status")), rs.getInt("attempts"),
                rs.getTimestamp("available_at").toInstant(), rs.getTimestamp("claimed_at").toInstant(),
                rs.getString("worker_id"), rs.getObject("claim_token", UUID.class),
                rs.getString("last_error_code"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                rs.getLong("actor_user_id"), rs.getLong("target_user_id"),
                rs.getTimestamp("occurred_at").toInstant());
    }

    private SafetyEvaluationJob mapJob(ResultSet rs, int rowNumber) throws SQLException {
        Timestamp claimedAt = rs.getTimestamp("claimed_at");
        return new SafetyEvaluationJob(
                rs.getLong("id"), rs.getLong("risk_signal_event_id"),
                SafetyEvaluationJobStatus.valueOf(rs.getString("status")), rs.getInt("attempts"),
                rs.getTimestamp("available_at").toInstant(),
                claimedAt == null ? null : claimedAt.toInstant(), rs.getString("worker_id"),
                rs.getObject("claim_token", UUID.class), rs.getString("last_error_code"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static String normalizeErrorCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A safety evaluation error code is required");
        }
        String normalized = value.trim();
        if (normalized.length() > 100 || !normalized.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException("Safety evaluation error must be a sanitized code");
        }
        return normalized;
    }

    public record ClaimedEvaluation(
            SafetyEvaluationJob job,
            long subjectUserId,
            long targetUserId,
            Instant eventOccurredAt
    ) { }
}
