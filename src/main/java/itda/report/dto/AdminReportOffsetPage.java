package itda.report.dto;

public record AdminReportOffsetPage(
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
