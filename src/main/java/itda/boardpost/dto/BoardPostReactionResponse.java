package itda.boardpost.dto;

import itda.boardpost.domain.BoardPostReactionType;

public record BoardPostReactionResponse(
        Long postId,
        BoardPostReactionType type,
        boolean reacted,
        long reactionCount
) {
}
