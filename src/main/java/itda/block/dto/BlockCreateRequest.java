package itda.block.dto;

import jakarta.validation.constraints.NotNull;

public record BlockCreateRequest(
        @NotNull Long targetPetId
) {}