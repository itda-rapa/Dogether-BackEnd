package itda.setlog.dto;

import itda.setlog.domain.Setlog;
import itda.setlog.domain.SetlogStatus;
import java.time.Instant;

public record SetlogCreateResponse(
        Long setlogId,
        Long authorPetId,
        Long mediaId,
        String caption,
        SetlogStatus status,
        Instant createdAt
) {

    public static SetlogCreateResponse from(Setlog setlog) {
        return new SetlogCreateResponse(
                setlog.getId(),
                setlog.getAuthorPet().getId(),
                setlog.getMedia().getId(),
                setlog.getCaption(),
                setlog.getStatus(),
                setlog.getCreatedAt()
        );
    }
}
