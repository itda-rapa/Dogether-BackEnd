package itda.boardpost.dto;

import java.util.List;

public record BoardPostUpdateRequest(
        boolean titlePresent,
        String title,
        boolean contentPresent,
        String content,
        boolean mediaIdsPresent,
        List<Long> mediaIds,
        boolean placeIdPresent,
        Integer placeId,
        long version
) {
    public BoardPostUpdateRequest(
            boolean titlePresent,
            String title,
            boolean contentPresent,
            String content,
            long version
    ) {
        this(titlePresent, title, contentPresent, content, false, List.of(), false, null, version);
    }

    public BoardPostUpdateRequest(
            boolean titlePresent,
            String title,
            boolean contentPresent,
            String content,
            boolean mediaIdsPresent,
            List<Long> mediaIds,
            long version
    ) {
        this(titlePresent, title, contentPresent, content, mediaIdsPresent, mediaIds, false, null, version);
    }
}
