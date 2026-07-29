package itda.report.domain;

import itda.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "reports")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reporter_user_id", nullable = false)
    private Long reporterUserId;

    @Column(name = "reporter_pet_id", nullable = false)
    private Long reporterPetId;

    @Column(name = "reported_user_id", nullable = false)
    private Long reportedUserId;

    @Column(name = "reported_pet_id", nullable = false)
    private Long reportedPetId;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 30)
    private ReportReason reasonCode;

    @Column(length = 500)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportStatus status;

    @Column(name = "reviewed_by_admin_id")
    private Long reviewedByAdminId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    public Report(Long reporterUserId, Long reporterPetId, Long reportedUserId, Long reportedPetId,
                  Long roomId, ReportReason reasonCode, String detail) {
        this.reporterUserId = reporterUserId;
        this.reporterPetId = reporterPetId;
        this.reportedUserId = reportedUserId;
        this.reportedPetId = reportedPetId;
        this.roomId = roomId;
        this.reasonCode = reasonCode;
        this.detail = detail;
        this.status = ReportStatus.OPEN;
    }
}