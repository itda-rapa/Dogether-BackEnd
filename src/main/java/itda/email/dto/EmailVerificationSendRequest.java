package itda.email.dto;

import itda.email.EmailVerificationPurpose;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EmailVerificationSendRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull EmailVerificationPurpose purpose
) {
    public EmailVerificationSendRequest {
        email = email == null ? null : email.trim();
    }
}
