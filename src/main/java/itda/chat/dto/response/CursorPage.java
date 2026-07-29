package itda.chat.dto.response;

public record CursorPage(String nextCursor, boolean hasNext) {

    public static CursorPage of(String nextCursor, boolean hasNext) {
        return new CursorPage(nextCursor, hasNext);
    }
}