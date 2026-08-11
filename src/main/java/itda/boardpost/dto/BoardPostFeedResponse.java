package itda.boardpost.dto;
import java.util.List;
public record BoardPostFeedResponse(List<BoardPostResponse> items, BoardPostCursorPage page) {}
