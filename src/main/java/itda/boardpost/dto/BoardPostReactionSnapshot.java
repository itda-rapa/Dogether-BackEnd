package itda.boardpost.dto;

public record BoardPostReactionSnapshot(
        long reactionCount,
        boolean reactedByMe,
        long helpfulCount,
        boolean helpfulByMe
) {

    public BoardPostReactionSnapshot(long reactionCount, boolean reactedByMe) {
        this(reactionCount, reactedByMe, 0, false);
    }

    public static BoardPostReactionSnapshot none() {
        return new BoardPostReactionSnapshot(0, false, 0, false);
    }
}
