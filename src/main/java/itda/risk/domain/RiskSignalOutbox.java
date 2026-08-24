package itda.risk.domain;

import itda.common.BaseEntity;
import itda.risk.contract.RiskSignalEventV1;
import itda.risk.contract.RiskSignalType;
import itda.risk.contract.RiskSourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "risk_signal_outbox", uniqueConstraints = {
        @UniqueConstraint(name = "uk_risk_signal_outbox_event_id", columnNames = "event_id"),
        @UniqueConstraint(name = "uk_risk_signal_outbox_source", columnNames = {"source_type", "source_id", "signal_type"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiskSignalOutbox extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 50)
    private RiskSourceType sourceType;

    @Column(name = "source_id", nullable = false)
    private long sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false, length = 50)
    private RiskSignalType signalType;

    @Column(name = "actor_user_id", nullable = false)
    private long actorUserId;

    @Column(name = "target_user_id", nullable = false)
    private long targetUserId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RiskSignalOutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_retry_at", nullable = false)
    private Instant nextRetryAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "published_at")
    private Instant publishedAt;
}
