package itda.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OAuthSignupRequest(
        @NotBlank @Size(max = 512) String signupToken,
        @NotBlank @Size(min = 2, max = 20) String nickname,
        @NotBlank @Size(max = 20) String neighborhoodCode
) {

    public OAuthSignupRequest {
        nickname = nickname == null ? null : nickname.trim();
        neighborhoodCode = neighborhoodCode == null ? null : neighborhoodCode.trim();
    }
}
