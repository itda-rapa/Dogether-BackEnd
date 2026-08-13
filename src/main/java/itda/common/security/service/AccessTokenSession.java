package itda.common.security.service;

import java.time.Instant;

public record AccessTokenSession(
        Long userId,
        Instant expiresAt
) {
}
