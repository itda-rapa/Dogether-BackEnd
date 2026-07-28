package itda.chat.dto.response;

import java.util.List;

public record ChatRoomListResponse(
        List<ChatRoomResponse> items,
        CursorPage page) {
}