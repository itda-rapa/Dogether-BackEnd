package itda.auth.dto;

import java.time.Instant;

public record OAuthSignupRequiredResponse(
        boolean profileCompletionRequired,
        String signupToken,
        Instant signupTokenExpiresAt
) {
}
