package itda.pet.dto;

import itda.pet.domain.Pet;
import itda.pet.domain.PetSex;
import itda.pet.domain.PetSizeCode;
import itda.pet.domain.PetStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PetResponse(
        Long petId,
        Long ownerUserId,
        String publicTag,
        String ownerPublicTag,
        String nickname,
        String breedName,
        PetSex sex,
        Boolean neutered,
        LocalDate birthDate,
        BigDecimal weightKg,
        PetSizeCode sizeCode,
        String bio,
        List<String> personalityTags,
        String careNote,
        String profileUrl,
        PetStatus status,
        Instant deletedAt,
        boolean verified,
        Instant verifiedAt,
        boolean active
) {

    public PetResponse {
        personalityTags = personalityTags == null
                ? List.of()
                : List.copyOf(personalityTags);
    }

    public static PetResponse from(
            Pet pet,
            boolean active,
            String profileUrl
    ) {
        return new PetResponse(
                pet.getId(),
                pet.getOwner().getId(),
                pet.getPublicTag(),
                pet.getOwner().getPublicTag(),
                pet.getNickname(),
                pet.getBreedName(),
                pet.getSex(),
                pet.getNeutered(),
                pet.getBirthDate(),
                pet.getWeightKg(),
                pet.getSizeCode(),
                pet.getBio(),
                pet.getPersonalityTags(),
                pet.getCareNote(),
                profileUrl,
                pet.getStatus(),
                pet.getDeletedAt(),
                false,
                null,
                active
        );
    }
}
