package itda.meetingcard.dto;

import itda.meetingcard.domain.MeetingCardType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OpenAPI {@code MeetingCardCreateRequest}.
 *
 * <p>확정 단계에서는 종류·장소·시각이 모두 필수다. 초안이 비어 있어도 사용자가 폼에서
 * 채워 보내기 때문이다. {@code draftId} 는 초안을 거치지 않고 바로 확정할 수 있어 nullable 이다.
 */
public record MeetingCardCreateRequest(
        @NotNull Long roomId,
        Long draftId,
        @NotNull MeetingCardType cardType,
        @NotBlank @Size(max = 500) String placeText,
        @NotNull Instant meetAt,
        List<Long> participantPetIds,
        UUID routeRequestId,
        @Min(1) @Max(1000) Integer participantCount
) {
    /** Backwards-compatible constructor for the DIRECT-chat contract. */
    public MeetingCardCreateRequest(Long roomId, Long draftId, MeetingCardType cardType,
                                    String placeText, Instant meetAt) {
        this(roomId, draftId, cardType, placeText, meetAt, null, null, null);
    }

    public MeetingCardCreateRequest(Long roomId, Long draftId, MeetingCardType cardType,
                                    String placeText, Instant meetAt,
                                    List<Long> participantPetIds) {
        this(roomId, draftId, cardType, placeText, meetAt, participantPetIds, null, null);
    }

    public MeetingCardCreateRequest(Long roomId, Long draftId, MeetingCardType cardType,
                                    String placeText, Instant meetAt,
                                    List<Long> participantPetIds, UUID routeRequestId) {
        this(roomId, draftId, cardType, placeText, meetAt, participantPetIds,
                routeRequestId, null);
    }
}
