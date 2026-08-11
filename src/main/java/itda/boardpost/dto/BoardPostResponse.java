package itda.boardpost.dto;
import itda.boardpost.domain.BoardPost;
import itda.pet.service.query.PetDisplaySummary;
import java.time.Instant;
public record BoardPostResponse(Long postId, Long boardId, BoardPostAuthorPetResponse authorPet, String title, String content, long version, Instant createdAt, Instant updatedAt) {
    public static BoardPostResponse of(BoardPost post, PetDisplaySummary pet) { return new BoardPostResponse(post.getId(), post.getBoardId(), BoardPostAuthorPetResponse.from(pet), post.getTitle(), post.getContent(), post.getVersion(), post.getCreatedAt(), post.getUpdatedAt()); }
}
