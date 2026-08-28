package itda.chat.dto.response;

import com.querydsl.core.annotations.QueryProjection;
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
        ChatMessageAttachmentResponse attachment,
        SharedSetlogResponse sharedSetlog,
        ChatMapMessageResponse map,
        UUID sharedRouteId,
        Long meetingCardId,
        String clientMessageId,
        Instant createdAt
) {

    @QueryProjection
    public ChatMessageResponse {
    }

    public ChatMessageResponse(
            Long messageId, Long roomId, String senderType, Long senderPetId,
            String senderPetNickname, String type, String body,
            ChatMessageAttachmentResponse attachment, SharedSetlogResponse sharedSetlog,
            ChatMapMessageResponse map, Long meetingCardId, String clientMessageId,
            Instant createdAt
    ) {
        this(messageId, roomId, senderType, senderPetId, senderPetNickname, type, body,
                attachment, sharedSetlog, map, null, meetingCardId, clientMessageId, createdAt);
    }
}
