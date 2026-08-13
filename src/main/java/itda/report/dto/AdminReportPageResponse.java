package itda.report.dto;

import java.util.List;

public record AdminReportPageResponse(
        List<ReportResponse> items,
        AdminReportOffsetPage page
) {
}
