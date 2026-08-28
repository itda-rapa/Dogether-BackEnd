package itda.meetingcard.dto.response;

import itda.meetingcard.domain.MeetingCard;
import itda.meetingcard.domain.MeetingCardStatus;
import itda.meetingcard.domain.MeetingCardType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OpenAPI {@code MeetingCard}. {@code participantPetIds} 는 M1 에서 정확히 두 개다.
 *
 * <p>{@code sourceDraftId} 는 내부 추적용이라 계약에 없고 노출하지 않는다.
 */
public record MeetingCardResponse(
        Long cardId,
        Long roomId,
        Long creatorPetId,
        List<Long> participantPetIds,
        List<OpenChatDraftParticipantResponse> participants,
        Integer participantCount,
        MeetingCardType cardType,
        String placeText,
        Instant meetAt,
        UUID routeRequestId,
        MeetingCardStatus status,
        Long canceledByPetId,
        Instant canceledAt,
        Instant createdAt
) {

    public static MeetingCardResponse of(MeetingCard card, List<Long> participantPetIds) {
        return of(card, participantPetIds, List.of());
    }

    public static MeetingCardResponse of(
            MeetingCard card,
            List<Long> participantPetIds,
            List<OpenChatDraftParticipantResponse> participants
    ) {
        return new MeetingCardResponse(
                card.getId(),
                card.getRoomId(),
                card.getCreatorPetId(),
                participantPetIds,
                participants,
                card.getParticipantCount(),
                card.getCardType(),
                card.getPlaceText(),
                card.getMeetAt(),
                card.getRouteRequestId(),
                card.getStatus(),
                card.getCanceledByPetId(),
                card.getCanceledAt(),
                card.getCreatedAt()
        );
    }
}
