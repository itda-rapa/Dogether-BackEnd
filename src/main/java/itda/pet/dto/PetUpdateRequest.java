package itda.pet.dto;

import itda.common.validation.NoEmoji;
import itda.pet.domain.PetSex;
import itda.pet.domain.PetSizeCode;
import itda.pet.service.PetUpdateCommand;
import itda.pet.service.PetUpdateCommand.PatchValue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public record PetUpdateRequest(
        @Schema(hidden = true)
        Set<Field> presentFields,

        @NotBlank
        @Size(max = 30)
        @NoEmoji
        String nickname,

        @Size(max = 100)
        @Schema(nullable = true)
        String breedName,

        @Schema(nullable = true)
        PetSex sex,

        @Schema(nullable = true)
        Boolean neutered,

        @Schema(nullable = true)
        LocalDate birthDate,

        @DecimalMin("0")
        @DecimalMax("999.99")
        @Digits(integer = 3, fraction = 2)
        @Schema(nullable = true)
        BigDecimal weightKg,

        @Schema(nullable = true)
        PetSizeCode sizeCode,

        @Size(max = 500)
        @Schema(nullable = true)
        String bio,

        @Size(max = 10)
        List<@NotNull String> personalityTags,

        @Size(max = 500)
        @Schema(nullable = true)
        String careNote
) {

    public PetUpdateRequest {
        presentFields = Set.copyOf(presentFields);
        nickname = nickname == null ? null : nickname.trim();
        personalityTags = personalityTags == null
                ? null
                : Collections.unmodifiableList(
                        new ArrayList<>(personalityTags)
                );
    }

    public boolean isPresent(Field field) {
        return presentFields.contains(field);
    }

    public PetUpdateCommand toCommand() {
        return new PetUpdateCommand(
                patch(Field.NICKNAME, nickname),
                patch(Field.BREED_NAME, breedName),
                patch(Field.SEX, sex),
                patch(Field.NEUTERED, neutered),
                patch(Field.BIRTH_DATE, birthDate),
                patch(Field.WEIGHT_KG, weightKg),
                patch(Field.SIZE_CODE, sizeCode),
                patch(Field.BIO, bio),
                patch(Field.PERSONALITY_TAGS, personalityTags),
                patch(Field.CARE_NOTE, careNote)
        );
    }

    private <T> PatchValue<T> patch(Field field, T value) {
        return isPresent(field)
                ? PatchValue.present(value)
                : PatchValue.missing();
    }

    @Schema(hidden = true)
    public enum Field {
        NICKNAME("nickname"),
        BREED_NAME("breedName"),
        SEX("sex"),
        NEUTERED("neutered"),
        BIRTH_DATE("birthDate"),
        WEIGHT_KG("weightKg"),
        SIZE_CODE("sizeCode"),
        BIO("bio"),
        PERSONALITY_TAGS("personalityTags"),
        CARE_NOTE("careNote");

        private final String jsonName;

        Field(String jsonName) {
            this.jsonName = jsonName;
        }

        public String jsonName() {
            return jsonName;
        }
    }
}
