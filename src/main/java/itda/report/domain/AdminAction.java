package itda.report.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@Entity
@Table(name = "admin_actions")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminAction {

    private static final String REPORT_TARGET_TYPE = "REPORT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_admin_id", nullable = false)
    private Long actorAdminId;

    @Column(name = "target_type", nullable = false, length = 30)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 50)
    private AdminActionType actionType;

    @Column(nullable = false, length = 500)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", columnDefinition = "jsonb")
    private Map<String, Object> beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", columnDefinition = "jsonb")
    private Map<String, Object> afterState;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private AdminAction(
            Long actorAdminId,
            Long targetId,
            AdminActionType actionType,
            String reason,
            Map<String, Object> beforeState,
            Map<String, Object> afterState
    ) {
        this.actorAdminId = actorAdminId;
        this.targetType = REPORT_TARGET_TYPE;
        this.targetId = targetId;
        this.actionType = actionType;
        this.reason = reason;
        this.beforeState = beforeState;
        this.afterState = afterState;
    }

    public static AdminAction forReport(
            Long actorAdminId,
            Long reportId,
            AdminActionType actionType,
            String reason,
            Map<String, Object> beforeState,
            Map<String, Object> afterState
    ) {
        return new AdminAction(
                actorAdminId,
                reportId,
                actionType,
                reason,
                beforeState,
                afterState
        );
    }
}
