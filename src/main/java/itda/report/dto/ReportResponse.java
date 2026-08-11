package itda.report.dto;

import itda.report.domain.Report;
import itda.report.domain.ReportReason;
import itda.report.domain.ReportStatus;
import java.time.Instant;

public record ReportResponse(
        Long reportId,
        Long roomId,
        Long reporterUserId,
        Long reportedUserId,
        ReportReason reasonCode,
        String detail,
        ReportStatus status,
        Long reviewedByAdminId,
        Instant reviewedAt,
        String resolutionNote,
        Instant createdAt
) {
    public static ReportResponse from(Report report) {
        return new ReportResponse(
                report.getId(),
                report.getRoomId(),
                report.getReporterUserId(),
                report.getReportedUserId(),
                report.getReasonCode(),
                report.getDetail(),
                report.getStatus(),
                report.getReviewedByAdminId(),
                report.getReviewedAt(),
                report.getResolutionNote(),
                report.getCreatedAt()
        );
    }
}
