package itda.risk.domain;

import itda.common.BaseEntity;
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
@Table(name = "risk_signal_events", uniqueConstraints =
        @UniqueConstraint(name = "uk_risk_signal_events_event_id", columnNames = "event_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiskSignalEvent extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 50, updatable = false)
    private RiskSourceType sourceType;

    @Column(name = "source_id", nullable = false, updatable = false)
    private long sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false, length = 50, updatable = false)
    private RiskSignalType signalType;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private long actorUserId;

    @Column(name = "target_user_id", nullable = false, updatable = false)
    private long targetUserId;

    @Column(nullable = false, updatable = false)
    private int score;

    @Column(name = "score_policy_version", nullable = false, updatable = false)
    private int scorePolicyVersion;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, String> metadata;
}
