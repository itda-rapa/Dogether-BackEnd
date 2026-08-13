package itda.meetingcard.dto.response;

import itda.meetingcard.domain.CardDraft;
import itda.meetingcard.domain.CardDraftFallbackReason;
import itda.meetingcard.domain.MeetingCardType;
import java.time.Instant;
import java.util.List;

public record OpenChatCardDraftResponse(
        Long draftId,
        Long roomId,
        Long requestedByPetId,
        MeetingCardType cardType,
        String placeText,
        String date,
        String time,
        Instant meetAt,
        boolean fallback,
        CardDraftFallbackReason fallbackReason,
        Instant createdAt,
        List<Long> participantPetIds,
        List<OpenChatDraftParticipantResponse> participants
) {
    public static OpenChatCardDraftResponse from(CardDraft draft, List<Long> participantPetIds) {
        return from(draft, participantPetIds, List.of());
    }

    public static OpenChatCardDraftResponse from(
            CardDraft draft,
            List<Long> participantPetIds,
            List<OpenChatDraftParticipantResponse> participants
    ) {
        return new OpenChatCardDraftResponse(
                draft.getId(), draft.getRoomId(), draft.getRequestedByPetId(),
                draft.getCardType(), draft.getPlaceText(),
                draft.getDate(), draft.getTime(), draft.getMeetAt(),
                draft.isFallback(), draft.getFallbackReason(),
                draft.getCreatedAt(), List.copyOf(participantPetIds), List.copyOf(participants));
    }
}
