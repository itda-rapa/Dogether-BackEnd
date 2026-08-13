package itda.meetingcard.dto.event;

import java.time.LocalDate;

public record OpenChatDraftRequestEvent(
        String requestId,
        Long roomId,
        Long requesterUserId,
        Long requesterPetId,
        LocalDate referenceDate
) {
}
