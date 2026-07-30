package itda.friend.dto.response;

import itda.chat.dto.response.CursorPage;
import java.util.List;

public record FriendRequestListResponse(
        List<FriendRequestResponse> items,
        CursorPage page
) {

    public FriendRequestListResponse {
        items = List.copyOf(items);
    }
}
