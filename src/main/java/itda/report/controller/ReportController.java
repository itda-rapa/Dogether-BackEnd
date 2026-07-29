package itda.report.controller;

import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.report.dto.ReportCreateRequest;
import itda.report.dto.ReportResponse;
import itda.report.service.ReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @PostMapping("/reports")
    public ResponseEntity<ApiResponse<ReportResponse>> createReport(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody ReportCreateRequest request
    ) {
        ReportService.CreateReportResult result = reportService.createReport(currentUser.id(), request);
        String message = "신고가 접수되었습니다.";
        if (result.created()) {
            return ResponseEntity.status(201).body(ApiResponse.created(result.report(), message));
        }
        return ResponseEntity.ok(ApiResponse.ok(result.report(), message));
    }
}
