package itda.petverification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ApplyPetVerificationRequest(
        @NotBlank @Pattern(regexp = ".*\\S.*") String petVerificationToken
) { }
