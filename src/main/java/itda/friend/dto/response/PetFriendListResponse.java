package itda.friend.dto.response;

import itda.chat.dto.response.CursorPage;
import java.util.List;

public record PetFriendListResponse(
        List<FriendPetListItemResponse> items,
        CursorPage page
) {

    public PetFriendListResponse {
        items = List.copyOf(items);
    }
}
