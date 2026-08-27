package itda.meetingcard.dto.event;

import itda.meetingcard.domain.MeetingCardType;
import java.time.Instant;
import java.util.List;

public record OpenChatDraftReadyEvent(
        String requestId,
        Long roomId,
        Long targetUserId,
        Status status,
        String message,
        List<Draft> drafts
) {
    public enum Status { COMPLETED, FAILED }

    public record Draft(
            Long draftId,
            Long roomId,
            Long requestedByPetId,
            MeetingCardType cardType,
            String placeText,
            String date,
            String time,
            Instant meetAt,
            boolean fallback,
            String fallbackReason,
            Instant createdAt,
            List<Long> participantPetIds
    ) {
    }
}
