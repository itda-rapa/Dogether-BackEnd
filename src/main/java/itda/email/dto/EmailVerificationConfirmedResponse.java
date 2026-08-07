package itda.email.dto;

import java.time.Instant;

public record EmailVerificationConfirmedResponse(
        String verificationToken,
        Instant expiresAt
) {
}
