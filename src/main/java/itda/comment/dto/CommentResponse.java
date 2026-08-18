package itda.comment.dto;

import itda.boardpost.dto.BoardPostAuthorPetResponse;
import itda.comment.domain.BoardPostComment;
import itda.pet.service.query.PetDisplaySummary;
import java.time.Instant;

public record CommentResponse(
        Long commentId,
        Long postId,
        BoardPostAuthorPetResponse authorPet,
        String content,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public static CommentResponse of(
            BoardPostComment comment,
            PetDisplaySummary authorPet
    ) {
        return new CommentResponse(
                comment.getId(),
                comment.getPostId(),
                BoardPostAuthorPetResponse.from(authorPet),
                comment.getContent(),
                comment.getVersion(),
                comment.getCreatedAt(),
                comment.getUpdatedAt()
        );
    }
}
