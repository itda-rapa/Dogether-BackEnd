package itda.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Provisional HTTP request contract until the M2 password-reset API is finalized.
 */
public record PasswordResetRequest(
        @NotBlank
        @Email
        @Size(max = 254)
        String email,

        @NotBlank
        @Size(min = 20, max = 256)
        String verificationToken,

        @NotBlank
        @Size(min = 10, max = 128)
        String newPassword
) {
    public PasswordResetRequest {
        email = email == null ? null : email.trim();
    }
}
