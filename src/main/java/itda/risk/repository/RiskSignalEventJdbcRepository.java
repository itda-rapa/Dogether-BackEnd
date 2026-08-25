package itda.risk.repository;

import itda.risk.contract.RiskSignalEventV1;
import itda.risk.policy.RiskScorePolicy.Decision;
import itda.risk.service.RiskSignalAggregate;
import itda.risk.service.RiskSignalEvaluationAggregate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
@RequiredArgsConstructor
public class RiskSignalEventJdbcRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public boolean insertIfAbsent(RiskSignalEventV1 event, Decision decision) {
        String metadata;
        try {
            metadata = objectMapper.writeValueAsString(event.metadata());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Failed to serialize risk signal metadata", exception);
        }
        List<Long> inserted = jdbc.queryForList("""
                    insert into risk_signal_events (
                        event_id, schema_version, source_type, source_id, signal_type,
                        actor_user_id, target_user_id, score, score_policy_version,
                        occurred_at, metadata
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                    on conflict (event_id) do nothing
                    returning id
                    """, Long.class,
                    event.eventId(), event.schemaVersion(), event.sourceType().name(), event.sourceId(),
                    event.signalType().name(), event.actorUserId(), event.targetUserId(),
                    decision.score(), decision.policyVersion(), Timestamp.from(event.occurredAt()),
                    metadata);
        return !inserted.isEmpty();
    }

    public RiskSignalAggregate aggregateForActor(
            long actorUserId, Instant fromInclusive, Instant toExclusive
    ) {
        return aggregate("actor_user_id = ?", actorUserId, fromInclusive, toExclusive);
    }

    public RiskSignalAggregate aggregateForTarget(
            long targetUserId, Instant fromInclusive, Instant toExclusive
    ) {
        return aggregate("target_user_id = ?", targetUserId, fromInclusive, toExclusive);
    }

    public RiskSignalAggregate aggregateForActorAndTarget(
            long actorUserId, long targetUserId, Instant fromInclusive, Instant toExclusive
    ) {
        return jdbc.queryForObject("""
                select count(*) as signal_count,
                       coalesce(sum(score), 0) as total_score,
                       min(occurred_at) as first_detected_at,
                       max(occurred_at) as last_detected_at
                  from risk_signal_events
                 where actor_user_id = ? and target_user_id = ?
                   and occurred_at >= ? and occurred_at < ?
                """, (resultSet, rowNumber) -> new RiskSignalAggregate(
                        resultSet.getLong("signal_count"),
                        resultSet.getLong("total_score"),
                        instantOrNull(resultSet.getTimestamp("first_detected_at")),
                        instantOrNull(resultSet.getTimestamp("last_detected_at"))),
                actorUserId, targetUserId, Timestamp.from(fromInclusive), Timestamp.from(toExclusive));
    }

    public Optional<Instant> findLatestOccurredAtForActorAndTarget(
            long actorUserId, long targetUserId, Instant toInclusive
    ) {
        return Optional.ofNullable(jdbc.queryForObject("""
                select max(occurred_at)
                  from risk_signal_events
                 where actor_user_id = ? and target_user_id = ? and occurred_at <= ?
                """, (resultSet, rowNumber) ->
                        instantOrNull(resultSet.getTimestamp(1)),
                actorUserId, targetUserId, Timestamp.from(toInclusive)));
    }

    public RiskSignalEvaluationAggregate aggregateEvaluationWindowForActorAndTarget(
            long actorUserId,
            long targetUserId,
            Instant fromExclusive,
            Instant toInclusive
    ) {
        return jdbc.queryForObject("""
                with matching as (
                    select id, signal_type, score, occurred_at
                      from risk_signal_events
                     where actor_user_id = ? and target_user_id = ?
                       and occurred_at > ? and occurred_at <= ?
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
                """, (resultSet, rowNumber) -> new RiskSignalEvaluationAggregate(
                        resultSet.getLong("signal_count"),
                        resultSet.getLong("total_score"),
                        instantOrNull(resultSet.getTimestamp("first_detected_at")),
                        instantOrNull(resultSet.getTimestamp("last_detected_at")),
                        resultSet.getLong("last_evaluated_event_id"),
                        resultSet.getString("primary_signal_type")),
                actorUserId, targetUserId,
                Timestamp.from(fromExclusive), Timestamp.from(toInclusive));
    }

    private RiskSignalAggregate aggregate(
            String idPredicate, long id, Instant fromInclusive, Instant toExclusive
    ) {
        String sql = """
                select count(*) as signal_count,
                       coalesce(sum(score), 0) as total_score,
                       min(occurred_at) as first_detected_at,
                       max(occurred_at) as last_detected_at
                  from risk_signal_events
                 where %s and occurred_at >= ? and occurred_at < ?
                """.formatted(idPredicate);
        return jdbc.queryForObject(sql, (resultSet, rowNumber) -> new RiskSignalAggregate(
                        resultSet.getLong("signal_count"),
                        resultSet.getLong("total_score"),
                        instantOrNull(resultSet.getTimestamp("first_detected_at")),
                        instantOrNull(resultSet.getTimestamp("last_detected_at"))),
                id, Timestamp.from(fromInclusive), Timestamp.from(toExclusive));
    }

    private static Instant instantOrNull(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
