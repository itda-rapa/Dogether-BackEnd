package itda.report.dto;

import itda.chat.domain.ChatMessage;
import java.time.Instant;

public record AdminReportMessageEvidence(
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

    public static AdminReportMessageEvidence from(ChatMessage message) {
        return new AdminReportMessageEvidence(
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
