package itda.media.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import itda.media.domain.StorageDeleteReason;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StorageDeleteJobClaimService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional
    public List<ClaimedDeleteJob> claim(int batchSize, Duration lease) {
        Instant now = clock.instant();
        UUID token = UUID.randomUUID();
        return jdbc.query("""
                with candidates as (
                    select id from storage_delete_jobs
                     where ((status in ('PENDING','RETRY') and next_retry_at <= ?)
                         or (status = 'PROCESSING' and claimed_at <= ?))
                     order by next_retry_at, id
                     for update skip locked
                     limit ?
                )
                update storage_delete_jobs job
                   set status='PROCESSING', claim_token=?, claimed_at=?,
                       attempts=attempts+1, updated_at=?
                  from candidates
                 where job.id=candidates.id
                returning job.id, job.object_key, job.object_version_id, job.reason, job.attempts,
                          job.claim_token, job.deleted_once_at
                """, StorageDeleteJobClaimService::map,
                Timestamp.from(now), Timestamp.from(now.minus(lease)), batchSize, token,
                Timestamp.from(now), Timestamp.from(now));
    }

    public boolean complete(ClaimedDeleteJob job) {
        Instant now = clock.instant();
        return jdbc.update("""
                update storage_delete_jobs set status='COMPLETED', completed_at=?,
                    claim_token=null, claimed_at=null, last_error=null, updated_at=?
                 where id=? and status='PROCESSING' and claim_token=?
                """, Timestamp.from(now), Timestamp.from(now), job.id(), job.claimToken()) == 1;
    }

    public boolean markDeletedOnce(ClaimedDeleteJob job, Instant confirmAt) {
        Instant now = clock.instant();
        return jdbc.update("""
                update storage_delete_jobs set status='RETRY', deleted_once_at=?,
                    next_retry_at=?, claim_token=null, claimed_at=null,
                    last_error=null, updated_at=?
                 where id=? and status='PROCESSING' and claim_token=?
                   and deleted_once_at is null
                """, Timestamp.from(now), Timestamp.from(confirmAt), Timestamp.from(now),
                job.id(), job.claimToken()) == 1;
    }

    public boolean renew(ClaimedDeleteJob job) {
        Instant now = clock.instant();
        return jdbc.update("""
                update storage_delete_jobs set claimed_at=?, updated_at=?
                 where id=? and status='PROCESSING' and claim_token=?
                """, Timestamp.from(now), Timestamp.from(now), job.id(), job.claimToken()) == 1;
    }

    public void retry(ClaimedDeleteJob job, Instant nextRetryAt, String error) {
        updateFailure(job, "RETRY", nextRetryAt, error);
    }

    public void fail(ClaimedDeleteJob job, String error) {
        updateFailure(job, "FAILED", clock.instant(), error);
    }

    private void updateFailure(ClaimedDeleteJob job, String status, Instant nextRetryAt, String error) {
        Instant now = clock.instant();
        jdbc.update("""
                update storage_delete_jobs set status=?, next_retry_at=?, last_error=?,
                    claim_token=null, claimed_at=null, updated_at=?
                 where id=? and status='PROCESSING' and claim_token=?
                """, status, Timestamp.from(nextRetryAt), sanitize(error), Timestamp.from(now),
                job.id(), job.claimToken());
    }

    private static ClaimedDeleteJob map(ResultSet rs, int row) throws SQLException {
        return new ClaimedDeleteJob(rs.getLong("id"), rs.getString("object_key"),
                rs.getString("object_version_id"),
                StorageDeleteReason.valueOf(rs.getString("reason")), rs.getInt("attempts"),
                rs.getObject("claim_token", UUID.class),
                rs.getTimestamp("deleted_once_at") != null);
    }

    private static String sanitize(String error) {
        if (error == null) return null;
        return error.substring(0, Math.min(error.length(), 500));
    }

    public record ClaimedDeleteJob(Long id, String objectKey, String objectVersionId,
                                   StorageDeleteReason reason,
                                   int attempts, UUID claimToken, boolean deletedOnce) {}
}
