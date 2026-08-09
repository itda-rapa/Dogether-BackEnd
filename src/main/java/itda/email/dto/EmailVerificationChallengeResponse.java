package itda.email.dto;

import java.time.Instant;

public record EmailVerificationChallengeResponse(
        String challengeId,
        Instant expiresAt,
        long resendAfterSeconds
) {
}
