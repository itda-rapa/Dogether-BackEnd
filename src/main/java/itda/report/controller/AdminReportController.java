package itda.report.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.report.domain.ReportStatus;
import itda.report.dto.AdminReportActionRequest;
import itda.report.dto.AdminReportDetailResponse;
import itda.report.dto.AdminReportPageResponse;
import itda.report.dto.ReportResponse;
import itda.report.service.AdminReportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/admin/reports")
@RequiredArgsConstructor
public class AdminReportController implements AdminReportSwaggerSupporter {

    private final AdminReportService adminReportService;

    @Override
    @GetMapping
    public ResponseEntity<ApiResponse<AdminReportPageResponse>> listReports(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                adminReportService.listReports(currentUser.id(), status, page, size),
                "신고 큐를 조회했습니다."
        ));
    }

    @Override
    @GetMapping("/{reportId}")
    public ResponseEntity<ApiResponse<AdminReportDetailResponse>> getReport(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long reportId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                adminReportService.getReport(currentUser.id(), reportId),
                "신고 증거를 조회했습니다."
        ));
    }

    @Override
    @PostMapping("/{reportId}/actions")
    public ResponseEntity<ApiResponse<ReportResponse>> resolveReport(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long reportId,
            @Valid @RequestBody AdminReportActionRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                adminReportService.resolveReport(currentUser.id(), reportId, request),
                "신고 처리를 완료했습니다."
        ));
    }
}
