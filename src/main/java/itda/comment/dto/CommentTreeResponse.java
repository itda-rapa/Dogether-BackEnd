package itda.comment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import itda.boardpost.dto.BoardPostAuthorPetResponse;
import java.time.Instant;
import java.util.List;

public record CommentTreeResponse(
        Long commentId,
        Long postId,
        Long parentCommentId,
        short depth,
        boolean deleted,
        @Schema(nullable = true) BoardPostAuthorPetResponse authorPet,
        @Schema(nullable = true) String content,
        @Schema(nullable = true) Long version,
        @Schema(nullable = true) Instant createdAt,
        @Schema(nullable = true) Instant updatedAt,
        List<CommentTreeResponse> replies
) {
}
