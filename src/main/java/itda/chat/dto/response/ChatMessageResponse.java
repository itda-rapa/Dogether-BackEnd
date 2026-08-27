package itda.chat.dto.response;

import com.querydsl.core.annotations.QueryProjection;
import java.time.Instant;

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
        Long meetingCardId,
        String clientMessageId,
        Instant createdAt
) {

    @QueryProjection
    public ChatMessageResponse {
    }
}
