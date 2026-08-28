package itda.chat.dto.event;

import itda.chat.dto.response.ChatMessageResponse;
import itda.chat.dto.response.ChatMapMessageResponse;
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
        ChatMapMessageResponse map,
        UUID sharedRouteId,
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
                message.map(),
                message.sharedRouteId(),
                message.meetingCardId(),
                message.clientMessageId(),
                message.createdAt()
        );
    }
}
