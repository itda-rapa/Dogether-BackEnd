package itda.pet.dto;

import itda.friend.domain.FriendRelationship;
import itda.pet.domain.PetSex;
import itda.pet.domain.PetSizeCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

public record PetPublicProfileResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Long petId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String publicTag,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String nickname,
        @Schema(nullable = true, types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String profileUrl,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean verified,
        @Schema(nullable = true, types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String breedName,
        @Schema(nullable = true, types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED)
        PetSex sex,
        @Schema(nullable = true, types = {"boolean", "null"}, requiredMode = Schema.RequiredMode.REQUIRED)
        Boolean neutered,
        @Schema(nullable = true, types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDate birthDate,
        @Schema(nullable = true, types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED)
        PetSizeCode sizeCode,
        @Schema(nullable = true, types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String bio,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> personalityTags,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long helpfulReceivedCount,
        @Schema(nullable = true, types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED)
        FriendRelationship relationship
) {

    public PetPublicProfileResponse {
        personalityTags = personalityTags == null
                ? List.of()
                : List.copyOf(personalityTags);
    }
}
