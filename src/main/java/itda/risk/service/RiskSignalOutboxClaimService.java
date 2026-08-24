package itda.risk.service;

import itda.risk.contract.RiskSignalType;
import itda.risk.contract.RiskSourceType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RiskSignalOutboxClaimService {
    private static final int MAX_ERROR_LENGTH = 500;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional
    public List<ClaimedRiskSignal> claim(int batchSize, Duration lease) {
        Instant now = clock.instant();
        UUID token = UUID.randomUUID();
        return jdbc.query("""
                with candidates as (
                    select id
                      from risk_signal_outbox
                     where ((status in ('PENDING', 'RETRY') and next_retry_at <= ?)
                         or (status = 'PROCESSING' and claimed_at <= ?))
                     order by next_retry_at, id
                     for update skip locked
                     limit ?
                )
                update risk_signal_outbox event
                   set status = 'PROCESSING', claim_token = ?, claimed_at = ?,
                       attempts = attempts + 1, updated_at = ?
                  from candidates
                 where event.id = candidates.id
                returning event.id, event.event_id, event.source_type, event.source_id,
                          event.signal_type, event.actor_user_id, event.occurred_at,
                          event.payload::text as payload, event.attempts, event.claim_token
                """, RiskSignalOutboxClaimService::map,
                Timestamp.from(now), Timestamp.from(now.minus(lease)), batchSize,
                token, Timestamp.from(now), Timestamp.from(now));
    }

    @Transactional
    public boolean markSent(ClaimedRiskSignal event) {
        Instant now = clock.instant();
        return jdbc.update("""
                update risk_signal_outbox
                   set status = 'SENT', published_at = ?, last_error = null,
                       claim_token = null, claimed_at = null, updated_at = ?
                 where id = ? and status = 'PROCESSING' and claim_token = ?
                """, Timestamp.from(now), Timestamp.from(now), event.id(), event.claimToken()) == 1;
    }

    @Transactional
    public boolean retry(ClaimedRiskSignal event, Instant nextRetryAt, String error) {
        return updateFailure(event, "RETRY", nextRetryAt, error);
    }

    @Transactional
    public boolean fail(ClaimedRiskSignal event, String error) {
        return updateFailure(event, "FAILED", clock.instant(), error);
    }

    private boolean updateFailure(
            ClaimedRiskSignal event,
            String status,
            Instant nextRetryAt,
            String error
    ) {
        Instant now = clock.instant();
        return jdbc.update("""
                update risk_signal_outbox
                   set status = ?, next_retry_at = ?, last_error = ?,
                       claim_token = null, claimed_at = null, updated_at = ?
                 where id = ? and status = 'PROCESSING' and claim_token = ?
                """, status, Timestamp.from(nextRetryAt), sanitize(error), Timestamp.from(now),
                event.id(), event.claimToken()) == 1;
    }

    private static ClaimedRiskSignal map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ClaimedRiskSignal(
                resultSet.getLong("id"),
                resultSet.getObject("event_id", UUID.class),
                RiskSourceType.valueOf(resultSet.getString("source_type")),
                resultSet.getLong("source_id"),
                RiskSignalType.valueOf(resultSet.getString("signal_type")),
                resultSet.getLong("actor_user_id"),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getString("payload"),
                resultSet.getInt("attempts"),
                resultSet.getObject("claim_token", UUID.class)
        );
    }

    private static String sanitize(String error) {
        if (error == null) {
            return null;
        }
        return error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
    }

    public record ClaimedRiskSignal(
            long id,
            UUID eventId,
            RiskSourceType sourceType,
            long sourceId,
            RiskSignalType signalType,
            long actorUserId,
            Instant occurredAt,
            String payload,
            int attempts,
            UUID claimToken
    ) {
    }
}
