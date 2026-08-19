package itda.comment.dto;

import java.util.List;

public record CommentListResponse(
        List<CommentResponse> items,
        CommentCursorPage page
) {
}
