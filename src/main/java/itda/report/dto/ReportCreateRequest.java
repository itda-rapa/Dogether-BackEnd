package itda.report.dto;

import itda.report.domain.ReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReportCreateRequest(
        @NotNull
        Long roomId,

        @NotNull
        ReportReason reasonCode,

        @Size(max = 500)
        String detail
) {}