package itda.setlog.dto;

import itda.setlog.domain.ReactionType;

public record SetlogReactionResponse(
        Long setlogId,
        ReactionType type,
        boolean reacted,
        int cuteCount,
        int likeCount
) {
}
