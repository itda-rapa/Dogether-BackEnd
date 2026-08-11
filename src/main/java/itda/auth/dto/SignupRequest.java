package itda.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank
        @Email
        @Size(max = 254)
        String email,

        @NotBlank
        @Size(min = 10, max = 128)
        String password,

        @NotBlank
        @Size(min = 2, max = 20)
        String nickname,

        @NotBlank
        @Size(max = 20)
        String neighborhoodCode,

        @NotBlank
        @Size(min = 20, max = 256)
        String verificationToken
) {

    public SignupRequest {
        email = email == null ? null : email.trim();
        nickname = nickname == null ? null : nickname.trim();
        neighborhoodCode = neighborhoodCode == null
                ? null
                : neighborhoodCode.trim();
    }
}
