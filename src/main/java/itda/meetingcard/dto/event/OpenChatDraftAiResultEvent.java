package itda.meetingcard.dto.event;

import java.util.List;

public record OpenChatDraftAiResultEvent(
        String requestId,
        Long roomId,
        Long requesterUserId,
        Long requesterPetId,
        Status status,
        String message,
        List<Draft> drafts
) {
    public enum Status { COMPLETED, FAILED }

    public record Draft(
            Integer candidateIndex,
            String meetingType,
            String date,
            String time,
            String place,
            List<Long> participantPetIds
    ) {
    }
}
