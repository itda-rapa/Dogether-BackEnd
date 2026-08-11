package itda.pet.dto;

import itda.common.validation.NoEmoji;
import itda.pet.domain.PetSex;
import itda.pet.domain.PetSizeCode;
import itda.pet.service.PetCreateCommand;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PetCreateRequest(
        @NotBlank
        @Size(max = 30)
        @NoEmoji
        String nickname,

        @Size(max = 100)
        String breedName,

        PetSex sex,

        Boolean neutered,

        LocalDate birthDate,

        @DecimalMin("0")
        @DecimalMax("999.99")
        @Digits(integer = 3, fraction = 2)
        BigDecimal weightKg,

        PetSizeCode sizeCode,

        @Size(max = 500)
        String bio,

        @Size(max = 10)
        List<@NotNull String> personalityTags,

        @Size(max = 500)
        String careNote
) {

    public PetCreateRequest {
        nickname = nickname == null ? null : nickname.trim();
    }

    public PetCreateCommand toCommand() {
        return new PetCreateCommand(
                nickname,
                breedName,
                sex,
                neutered,
                birthDate,
                weightKg,
                sizeCode,
                bio,
                personalityTags,
                careNote
        );
    }
}
