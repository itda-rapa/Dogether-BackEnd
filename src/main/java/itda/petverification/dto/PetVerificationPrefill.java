package itda.petverification.dto;

import itda.pet.domain.PetSex;
import java.time.LocalDate;

public record PetVerificationPrefill(
        String nickname,
        String breedName,
        LocalDate birthDate,
        PetSex sex,
        Boolean neutered
) { }
