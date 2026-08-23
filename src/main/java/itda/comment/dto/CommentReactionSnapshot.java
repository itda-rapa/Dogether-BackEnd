package itda.comment.dto;

public record CommentReactionSnapshot(long helpfulCount, boolean helpfulByMe) {

    public static CommentReactionSnapshot none() {
        return new CommentReactionSnapshot(0, false);
    }
}
