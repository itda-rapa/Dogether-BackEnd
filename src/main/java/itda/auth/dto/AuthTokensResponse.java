package itda.auth.dto;

import itda.common.security.dto.IssuedTokens;
import java.time.Instant;

public record AuthTokensResponse(
        String accessToken,
        String refreshToken,
        Instant accessTokenExpiresAt
) {

    public static AuthTokensResponse from(IssuedTokens tokens) {
        return new AuthTokensResponse(
                tokens.accessToken(),
                tokens.refreshToken(),
                tokens.accessTokenExpiresAt()
        );
    }
}
