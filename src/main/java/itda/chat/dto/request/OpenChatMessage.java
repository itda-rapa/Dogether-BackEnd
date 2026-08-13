package itda.chat.dto.request;

import java.time.LocalDateTime;
import java.util.UUID;

public record OpenChatMessage(
        UUID requestId,
        Long roomId,
        Long senderId,
        String message,
        LocalDateTime sentAt
) {
}
