package itda.boardpost.dto;

import itda.boardpost.domain.BoardPost;
import itda.pet.service.query.PetDisplaySummary;
import java.time.Instant;
import java.util.List;

public record BoardPostResponse(
        Long postId,
        Long boardId,
        BoardPostAuthorPetResponse authorPet,
        String title,
        String content,
        List<BoardPostImageResponse> images,
        long reactionCount,
        boolean reactedByMe,
        long helpfulCount,
        boolean helpfulByMe,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public BoardPostResponse(
            Long postId,
            Long boardId,
            BoardPostAuthorPetResponse authorPet,
            String title,
            String content,
            List<BoardPostImageResponse> images,
            long reactionCount,
            boolean reactedByMe,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(
                postId, boardId, authorPet, title, content, images,
                reactionCount, reactedByMe, 0, false, version, createdAt, updatedAt
        );
    }

    public static BoardPostResponse of(
            BoardPost post,
            PetDisplaySummary pet,
            List<BoardPostImageResponse> images,
            BoardPostReactionSnapshot reaction
    ) {
        return new BoardPostResponse(
                post.getId(),
                post.getBoardId(),
                BoardPostAuthorPetResponse.from(pet),
                post.getTitle(),
                post.getContent(),
                List.copyOf(images),
                reaction.reactionCount(),
                reaction.reactedByMe(),
                reaction.helpfulCount(),
                reaction.helpfulByMe(),
                post.getVersion(),
                post.getCreatedAt(),
                post.getUpdatedAt()
        );
    }

}
