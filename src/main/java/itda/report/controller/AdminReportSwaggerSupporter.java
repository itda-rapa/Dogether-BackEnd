package itda.report.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.report.domain.ReportStatus;
import itda.report.dto.AdminReportActionRequest;
import itda.report.dto.AdminReportDetailResponse;
import itda.report.dto.AdminReportPageResponse;
import itda.report.dto.ReportResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;

@Tag(name = "Admin", description = "관리자 신고 처리 API")
@SecurityRequirement(name = "bearerAuth")
public interface AdminReportSwaggerSupporter {

    @Operation(summary = "관리자 신고 큐 조회")
    ResponseEntity<ApiResponse<AdminReportPageResponse>> listReports(
            @Parameter(hidden = true) CurrentUser currentUser,
            ReportStatus status,
            @Min(0) int page,
            @Min(1) @Max(100) int size
    );

    @Operation(summary = "관리자 신고 증거 조회")
    ResponseEntity<ApiResponse<AdminReportDetailResponse>> getReport(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "신고 ID")
            Long reportId
    );

    @Operation(summary = "관리자 신고 처리")
    ResponseEntity<ApiResponse<ReportResponse>> resolveReport(
            @Parameter(hidden = true) CurrentUser currentUser,
            @Parameter(description = "신고 ID")
            Long reportId,
            @Valid AdminReportActionRequest request
    );
}
