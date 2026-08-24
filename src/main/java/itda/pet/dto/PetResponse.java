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
        long version,
        boolean verified,
        Instant verifiedAt,
        boolean active,
        long helpfulReceivedCount
) {

    public PetResponse(
            Long petId, Long ownerUserId, String publicTag, String ownerPublicTag,
            String nickname, String breedName, PetSex sex, Boolean neutered,
            LocalDate birthDate, BigDecimal weightKg, PetSizeCode sizeCode, String bio,
            List<String> personalityTags, String careNote, String profileUrl,
            PetStatus status, Instant deletedAt, boolean verified, Instant verifiedAt,
            boolean active
    ) {
        this(
                petId, ownerUserId, publicTag, ownerPublicTag, nickname, breedName, sex, neutered,
                birthDate, weightKg, sizeCode, bio, personalityTags, careNote, profileUrl, status,
                deletedAt, 0, verified, verifiedAt, active, 0
        );
    }

    public PetResponse {
        personalityTags = personalityTags == null
                ? List.of()
                : List.copyOf(personalityTags);
    }

    public static PetResponse from(
            Pet pet,
            boolean active,
            String profileUrl,
            Instant verifiedAt
    ) {
        return from(pet, active, profileUrl, verifiedAt, 0);
    }

    public static PetResponse from(
            Pet pet,
            boolean active,
            String profileUrl,
            Instant verifiedAt,
            long helpfulReceivedCount
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
                pet.getVersion(),
                verifiedAt != null,
                verifiedAt,
                active,
                helpfulReceivedCount
        );
    }
}
