package itda.dashboard.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.dashboard.dto.AdminDashboardResponse;
import itda.dashboard.service.AdminDashboardQueryService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/dashboard")
@RequiredArgsConstructor
@Tag(name = "Admin Dashboard", description = "관리자 서비스·Safety 운영 통계 API")
@SecurityRequirement(name = "bearerAuth")
public class AdminDashboardController {

    private final AdminDashboardQueryService queryService;

    @GetMapping
    @Operation(summary = "관리자 Dashboard 조회",
            description = "Asia/Seoul 날짜 기준 통계와 최근 운영 항목을 조회합니다. "
                    + "기간은 기본 7일, 최대 90일이며 from과 to는 함께 입력해야 합니다.")
    public ResponseEntity<ApiResponse<AdminDashboardResponse>> get(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Parameter(description = "조회 시작일(포함)", example = "2026-08-14")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일(포함)", example = "2026-08-20")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                queryService.get(currentUser.id(), from, to),
                "관리자 Dashboard 조회 성공"));
    }
}
