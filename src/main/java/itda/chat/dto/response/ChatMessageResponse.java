package itda.chat.dto.response;

import itda.chat.domain.ChatMessage;
import itda.chat.domain.SenderType;
import java.time.Instant;

public record ChatMessageResponse(
        Long messageId,
        Long roomId,
        String senderType,
        Long senderPetId,
        String type,
        String body,
        Long meetingCardId,
        String clientMessageId,
        Instant createdAt
) {

    public static ChatMessageResponse from(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRoom().getId(),
                message.getSenderType().name(),
                message.getSenderPetId(),
                message.getType().name(),
                message.getBody(),
                message.getMeetingCardId(),
                message.getClientMessageId(),
                message.getCreatedAt()
        );
    }
}