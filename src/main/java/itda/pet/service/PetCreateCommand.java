package itda.pet.service;

import itda.pet.domain.PetSex;
import itda.pet.domain.PetSizeCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PetCreateCommand(
        String nickname,
        String breedName,
        PetSex sex,
        Boolean neutered,
        LocalDate birthDate,
        BigDecimal weightKg,
        PetSizeCode sizeCode,
        String bio,
        List<String> personalityTags,
        String careNote
) {

    public PetCreateCommand {
        personalityTags = personalityTags == null
                ? null
                : List.copyOf(personalityTags);
    }
}
