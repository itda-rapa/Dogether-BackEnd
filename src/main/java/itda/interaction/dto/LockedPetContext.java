package itda.interaction.dto;

import itda.pet.domain.PetStatus;
import java.time.Instant;

public record LockedPetContext(
        Long petId,
        Long ownerUserId,
        PetStatus status,
        Instant deletedAt
) {
}
