package itda.petverification.dto;

import java.time.Instant;

public record PetVerificationResponse(
        String verificationToken,
        Instant expiresAt,
        PetVerificationPrefill petPrefill
) { }
