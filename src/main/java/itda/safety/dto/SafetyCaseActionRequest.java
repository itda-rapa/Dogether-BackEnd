package itda.safety.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SafetyCaseActionRequest(
        @NotBlank @Size(max = 30) String actionType,
        @NotBlank @Size(max = 500) String reason
) {
}
