package itda.oauth.service;

public record OAuthSignupCommand(
        String signupToken,
        String nickname,
        String neighborhoodCode
) {
}
