package itda.setlog.dto;

import java.util.List;

public record SetlogListResponse(
        List<SetlogResponse> items,
        String nextCursor,
        boolean hasNext
) {

    public SetlogListResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
