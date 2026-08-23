package itda.comment.dto;

import itda.comment.domain.CommentReactionType;

public record CommentReactionResponse(
        Long commentId,
        CommentReactionType type,
        boolean reacted,
        long reactionCount
) {
}
