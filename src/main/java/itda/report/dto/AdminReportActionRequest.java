package itda.report.dto;

import itda.report.domain.AdminActionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminReportActionRequest(
        @NotNull AdminActionType actionType,
        @NotBlank @Size(max = 500) String reason
) {
}
