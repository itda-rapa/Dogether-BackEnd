package itda.setlog.dto;

import itda.setlog.domain.ReactionType;
import java.time.Instant;
import java.util.List;

public record SetlogResponse(
        Long setlogId,
        SetlogSource source,
        SetlogAuthorPetResponse authorPet,
        String mediaUrl,
        Instant mediaUrlExpiresAt,
        String caption,
        int cuteCount,
        int likeCount,
        List<ReactionType> myReactions,
        boolean canInteract,
        Instant createdAt
) {

    public SetlogResponse {
        myReactions = myReactions == null
                ? List.of()
                : List.copyOf(myReactions);
    }

    public SetlogResponse(
            Long setlogId,
            SetlogAuthorPetResponse authorPet,
            String mediaUrl,
            Instant mediaUrlExpiresAt,
            String caption,
            int cuteCount,
            int likeCount,
            List<ReactionType> myReactions,
            boolean canInteract,
            Instant createdAt
    ) {
        this(
                setlogId,
                SetlogSource.SEED,
                authorPet,
                mediaUrl,
                mediaUrlExpiresAt,
                caption,
                cuteCount,
                likeCount,
                myReactions,
                canInteract,
                createdAt
        );
    }
}
