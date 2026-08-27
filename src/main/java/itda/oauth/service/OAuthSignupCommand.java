package itda.oauth.service;

import java.math.BigDecimal;

public record OAuthSignupCommand(
        String signupToken,
        String nickname,
        String neighborhoodCode,
        BigDecimal weightKg
) {

    public OAuthSignupCommand(
            String signupToken,
            String nickname,
            String neighborhoodCode
    ) {
        this(signupToken, nickname, neighborhoodCode, null);
    }
}
