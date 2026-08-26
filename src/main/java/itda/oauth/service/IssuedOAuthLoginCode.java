package itda.oauth.service;

import java.time.Instant;

/** Raw code is returned exactly once to the browser callback layer and is never persisted. */
public record IssuedOAuthLoginCode(String loginCode, Instant expiresAt) {
}
