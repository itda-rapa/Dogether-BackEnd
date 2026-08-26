package itda.oauth.service;

import java.time.Instant;

public record IssuedOAuthSignupToken(String signupToken, Instant expiresAt) {
}
