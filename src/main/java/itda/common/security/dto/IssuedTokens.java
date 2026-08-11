package itda.common.security.dto;

import java.time.Instant;

public record IssuedTokens(
        String accessToken,
        String refreshToken,
        Instant accessTokenExpiresAt
) {
}
