package itda.meetingcard.dto.response;

import itda.meetingcard.domain.MeetingCard;
import itda.meetingcard.domain.MeetingCardStatus;
import itda.meetingcard.domain.MeetingCardType;
import java.time.Instant;
import java.util.List;

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
        MeetingCardType cardType,
        String placeText,
        Instant meetAt,
        MeetingCardStatus status,
        Long canceledByPetId,
        Instant canceledAt,
        Instant createdAt
) {

    public static MeetingCardResponse of(MeetingCard card, List<Long> participantPetIds) {
        return new MeetingCardResponse(
                card.getId(),
                card.getRoomId(),
                card.getCreatorPetId(),
                participantPetIds,
                card.getCardType(),
                card.getPlaceText(),
                card.getMeetAt(),
                card.getStatus(),
                card.getCanceledByPetId(),
                card.getCanceledAt(),
                card.getCreatedAt()
        );
    }
}
