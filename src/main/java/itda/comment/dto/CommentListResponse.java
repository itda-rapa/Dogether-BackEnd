package itda.comment.dto;

import java.util.List;

public record CommentListResponse(
        List<CommentTreeResponse> items,
        CommentCursorPage page
) {
}
