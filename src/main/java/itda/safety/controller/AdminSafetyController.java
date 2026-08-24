package itda.safety.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import itda.common.dto.ApiResponse;
import itda.common.security.CurrentUser;
import itda.risk.contract.RiskSignalType;
import itda.safety.domain.SafetyCaseStatus;
import itda.safety.dto.SafetyCaseActionRequest;
import itda.safety.dto.SafetyCaseDetailResponse;
import itda.safety.dto.SafetyCasePageResponse;
import itda.safety.dto.SafetyCaseResponse;
import itda.safety.dto.SafetyEvidencePageResponse;
import itda.safety.service.AdminSafetyActionService;
import itda.safety.service.AdminSafetyEvidenceService;
import itda.safety.service.AdminSafetyQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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
@RequestMapping("/admin/safety/cases")
@RequiredArgsConstructor
@Tag(name = "Admin Safety", description = "관리자 안전 검토 Queue 및 Evidence 감사 API")
@SecurityRequirement(name = "bearerAuth")
public class AdminSafetyController {

    private final AdminSafetyQueryService queryService;
    private final AdminSafetyActionService actionService;
    private final AdminSafetyEvidenceService evidenceService;

    @GetMapping
    @Operation(summary = "안전 검토 Queue 조회",
            description = "생성 시각 기반 cursor로 SafetyCase를 조회합니다. ADMIN과 SUPER_ADMIN만 호출할 수 있습니다.")
    public ResponseEntity<ApiResponse<SafetyCasePageResponse>> list(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(defaultValue = "OPEN") SafetyCaseStatus status,
            @RequestParam(required = false) RiskSignalType signalType,
            @RequestParam(required = false) Long subjectUserId,
            @RequestParam(required = false) Long targetUserId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                queryService.list(currentUser.id(), status, signalType, subjectUserId,
                        targetUserId, from, to, cursor, size),
                "안전 검토 큐를 조회했습니다."));
    }

    @GetMapping("/{caseId}")
    @Operation(summary = "안전 검토 상세 조회",
            description = "Case snapshot, 최근 RiskSignal과 관리자 처리 이력을 조회합니다.")
    public ResponseEntity<ApiResponse<SafetyCaseDetailResponse>> detail(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable @Min(1) long caseId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                queryService.detail(currentUser.id(), caseId),
                "안전 검토 건을 조회했습니다."));
    }

    @PostMapping("/{caseId}/actions")
    @Operation(summary = "안전 검토 처리",
            description = "OPEN 또는 REVIEWING Case를 DISMISSED/WARNING_RECORDED로 종료하고 append-only action을 남깁니다.")
    public ResponseEntity<ApiResponse<SafetyCaseResponse>> resolve(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable @Min(1) long caseId,
            @Valid @RequestBody SafetyCaseActionRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                actionService.resolve(currentUser.id(), caseId, request),
                "안전 검토 건을 처리했습니다."));
    }

    @GetMapping("/{caseId}/evidence")
    @Operation(summary = "안전 검토 Evidence 조회",
            description = "USER_BLOCK/GREETING 원천의 안전한 요약만 반환하며 조회 시도는 감사 로그에 기록합니다.")
    public ResponseEntity<ApiResponse<SafetyEvidencePageResponse>> evidence(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable @Min(1) long caseId,
            @RequestParam @NotBlank @Size(max = 500) String purpose,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                evidenceService.evidence(currentUser.id(), caseId, purpose, cursor, size),
                "안전 검토 증거를 조회했습니다."));
    }
}
