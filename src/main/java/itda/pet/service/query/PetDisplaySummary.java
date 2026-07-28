package itda.pet.service.query;

import itda.pet.domain.PetStatus;
import java.time.Instant;

public record PetDisplaySummary(
        Long petId,
        Long ownerUserId,
        String publicTag,
        String nickname,
        String profileUrl,
        boolean verified,
        PetStatus status,
        Instant deletedAt
) {
}
