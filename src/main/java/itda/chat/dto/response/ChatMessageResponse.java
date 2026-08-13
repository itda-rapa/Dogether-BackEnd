package itda.chat.dto.response;

import com.querydsl.core.annotations.QueryProjection;
import itda.chat.domain.ChatMessage;
import itda.chat.domain.SenderType;
import java.time.Instant;
import java.util.UUID;

public record ChatMessageResponse(
        Long messageId,
        Long roomId,
        String senderType,
        Long senderPetId,
        String senderPetNickname,
        String type,
        String body,
        Long meetingCardId,
        String clientMessageId,
        Instant createdAt
) {

    @QueryProjection
    public ChatMessageResponse{}

    public static ChatMessageResponse from(ChatMessage message, String senderPetNickname) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRoom().getId(),
                message.getSenderType().name(),
                message.getSenderPetId(),
                senderPetNickname,
                message.getType().name(),
                message.getBody(),
                message.getMeetingCardId(),
                message.getClientMessageId(),
                message.getCreatedAt()
        );
    }
}
