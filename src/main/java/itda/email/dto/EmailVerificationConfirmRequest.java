package itda.email.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record EmailVerificationConfirmRequest(
        @NotBlank String challengeId,
        @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code
) {
}
