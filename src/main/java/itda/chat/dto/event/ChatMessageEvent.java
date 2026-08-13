package itda.chat.dto.event;

import itda.chat.dto.response.ChatMessageResponse;
import java.time.Instant;
import java.util.UUID;

public record ChatMessageEvent(
        UUID requestId,
        Long messageId,
        Long roomId,
        Long senderPetId,
        String senderPetNickname,
        String senderType,
        String type,
        String body,
        Long meetingCardId,
        String clientMessageId,
        Instant sentAt
) {

    public static ChatMessageEvent from(ChatMessageResponse message) {
        return new ChatMessageEvent(
                UUID.randomUUID(),
                message.messageId(),
                message.roomId(),
                message.senderPetId(),
                message.senderPetNickname(),
                message.senderType(),
                message.type(),
                message.body(),
                message.meetingCardId(),
                message.clientMessageId(),
                message.createdAt()
        );
    }
}
