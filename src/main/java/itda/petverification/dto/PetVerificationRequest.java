package itda.petverification.dto;

import itda.petverification.PetVerificationFlowType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record PetVerificationRequest(
        @NotNull PetVerificationFlowType flowType,
        Long petId,
        @NotNull PetVerificationIdentifierType identifierType,
        @NotBlank String identifier,
        String ownerName,
        LocalDate ownerBirthDate
) { }
