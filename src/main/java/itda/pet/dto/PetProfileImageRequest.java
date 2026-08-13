package itda.pet.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PetProfileImageRequest(
        @NotNull @Positive Long mediaId
) {
}
