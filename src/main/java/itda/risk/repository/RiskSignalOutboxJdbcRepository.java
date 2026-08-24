package itda.risk.repository;

import itda.risk.contract.RiskSignalEventV1;
import java.sql.Timestamp;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** 논리 키 충돌을 DB에서 무시하여 select-then-insert 경쟁 조건을 피한다. */
@Repository
@RequiredArgsConstructor
public class RiskSignalOutboxJdbcRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public void insertIfAbsent(RiskSignalEventV1 event) {
        try {
            jdbcTemplate.update("""
                    insert into risk_signal_outbox
                        (event_id, schema_version, source_type, source_id, signal_type,
                         actor_user_id, target_user_id, occurred_at, payload,
                         status, attempts, next_retry_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), 'PENDING', 0, ?)
                    on conflict (source_type, source_id, signal_type) do nothing
                    """,
                    event.eventId(),
                    event.schemaVersion(),
                    event.sourceType().name(),
                    event.sourceId(),
                    event.signalType().name(),
                    event.actorUserId(),
                    event.targetUserId(),
                    Timestamp.from(event.occurredAt()),
                    objectMapper.writeValueAsString(event),
                    Timestamp.from(event.occurredAt()));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to enqueue risk signal outbox event", exception);
        }
    }
}
