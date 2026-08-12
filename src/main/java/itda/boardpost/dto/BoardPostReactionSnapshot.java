package itda.boardpost.dto;

public record BoardPostReactionSnapshot(
        long reactionCount,
        boolean reactedByMe
) {

    public static BoardPostReactionSnapshot none() {
        return new BoardPostReactionSnapshot(0, false);
    }
}
