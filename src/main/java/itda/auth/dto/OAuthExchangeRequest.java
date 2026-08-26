package itda.auth.dto;

import itda.oauth.domain.OAuthProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OAuthExchangeRequest(
        @NotNull OAuthProvider provider,
        @NotBlank @Size(max = 512) String loginCode
) {
}
