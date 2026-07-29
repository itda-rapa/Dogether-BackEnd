package itda.user.dto;

import jakarta.validation.constraints.NotNull;

public record ActivePetUpdateRequest(
        @NotNull Long petId
) {
}
