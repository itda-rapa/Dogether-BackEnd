package itda.report.dto;

import java.util.List;

public record AdminReportDetailResponse(
        ReportResponse report,
        AdminReportPartyEvidence reporter,
        AdminReportPartyEvidence reported,
        AdminReportRoomEvidence room,
        List<AdminReportMessageEvidence> messages
) {
}
