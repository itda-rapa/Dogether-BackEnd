package itda.pet.service;

import itda.pet.domain.PetSex;
import itda.pet.domain.PetSizeCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record PetUpdateCommand(
        PatchValue<String> nickname,
        PatchValue<String> breedName,
        PatchValue<PetSex> sex,
        PatchValue<Boolean> neutered,
        PatchValue<LocalDate> birthDate,
        PatchValue<BigDecimal> weightKg,
        PatchValue<PetSizeCode> sizeCode,
        PatchValue<String> bio,
        PatchValue<List<String>> personalityTags,
        PatchValue<String> careNote
) {

    public PetUpdateCommand {
        Objects.requireNonNull(nickname);
        Objects.requireNonNull(breedName);
        Objects.requireNonNull(sex);
        Objects.requireNonNull(neutered);
        Objects.requireNonNull(birthDate);
        Objects.requireNonNull(weightKg);
        Objects.requireNonNull(sizeCode);
        Objects.requireNonNull(bio);
        Objects.requireNonNull(personalityTags);
        Objects.requireNonNull(careNote);

        if (personalityTags.present() && personalityTags.value() != null) {
            personalityTags = PatchValue.present(
                    List.copyOf(personalityTags.value())
            );
        }
    }

    public boolean hasAnyPresentField() {
        return nickname.present()
                || breedName.present()
                || sex.present()
                || neutered.present()
                || birthDate.present()
                || weightKg.present()
                || sizeCode.present()
                || bio.present()
                || personalityTags.present()
                || careNote.present();
    }

    public record PatchValue<T>(boolean present, T value) {

        public static <T> PatchValue<T> missing() {
            return new PatchValue<>(false, null);
        }

        public static <T> PatchValue<T> present(T value) {
            return new PatchValue<>(true, value);
        }
    }
}
