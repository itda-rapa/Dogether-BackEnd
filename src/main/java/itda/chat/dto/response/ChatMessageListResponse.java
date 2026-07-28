package itda.chat.dto.response;

import java.util.List;

public record ChatMessageListResponse(
        List<ChatMessageResponse> items,
        Long nextAfterMessageId,
        boolean hasMore
) {
}