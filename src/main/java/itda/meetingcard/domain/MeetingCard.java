package itda.meetingcard.domain;

import itda.common.BaseEntity;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 확정된 약속 카드. {@code OPEN -> CANCELED} 단방향이며 M1 에 수정은 없다.
 *
 * <p>Pet·Room 은 scalar id 로 들고 있다. Chat·Report 와 같은 관행이다.
 */
@Getter
@Entity
@Table(name = "meeting_cards")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeetingCard extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "creator_pet_id", nullable = false)
    private Long creatorPetId;

    /**
     * 확정에 사용한 초안. 초안 없이 직접 확정할 수 있어 nullable 이고, DB 의
     * {@code uk_meeting_card_source_draft} 가 한 초안이 카드를 두 번 만들지 못하게 막는다.
     */
    @Column(name = "source_draft_id")
    private Long sourceDraftId;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_type", nullable = false, length = 20)
    private MeetingCardType cardType;

    @Column(name = "place_text", nullable = false, length = 500)
    private String placeText;

    @Column(name = "meet_at", nullable = false)
    private Instant meetAt;

    @Column(name = "route_request_id")
    private UUID routeRequestId;

    @Column(name = "participant_count", nullable = false)
    private Integer participantCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MeetingCardStatus status;

    @Column(name = "canceled_by_pet_id")
    private Long canceledByPetId;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    public MeetingCard(Long roomId,
                       Long creatorPetId,
                       Long sourceDraftId,
                       MeetingCardType cardType,
                       String placeText,
                       Instant meetAt,
                       UUID routeRequestId,
                       int participantCount) {
        this.roomId = roomId;
        this.creatorPetId = creatorPetId;
        this.sourceDraftId = sourceDraftId;
        this.cardType = cardType;
        this.placeText = placeText;
        this.meetAt = meetAt;
        this.routeRequestId = routeRequestId;
        this.participantCount = participantCount;
        this.status = MeetingCardStatus.OPEN;
    }

    public MeetingCard(Long roomId, Long creatorPetId, Long sourceDraftId,
                       MeetingCardType cardType, String placeText, Instant meetAt,
                       UUID routeRequestId) {
        this(roomId, creatorPetId, sourceDraftId, cardType, placeText, meetAt,
                routeRequestId, 2);
    }

    public MeetingCard(Long roomId, Long creatorPetId, Long sourceDraftId,
                       MeetingCardType cardType, String placeText, Instant meetAt) {
        this(roomId, creatorPetId, sourceDraftId, cardType, placeText, meetAt, null, 2);
    }

    /**
     * 취소. 상태와 취소 흔적을 함께 세워 DB 의 {@code ck_meeting_card_cancel} 을 만족시킨다.
     *
     * <p>이미 취소된 카드를 다시 취소하면 409 로 거절한다. 동시 취소에서 한쪽만 성공해야
     * 하므로 호출부는 잠금을 잡은 뒤 이 메서드를 불러야 한다. 이 검사만으로는 경합을
     * 막을 수 없다.
     */
    public void cancel(Long canceledByPetId, Instant canceledAt) {
        if (status == MeetingCardStatus.CANCELED) {
            throw new BusinessException(ErrorCode.MEETING_CARD_ALREADY_CANCELED);
        }
        this.status = MeetingCardStatus.CANCELED;
        this.canceledByPetId = canceledByPetId;
        this.canceledAt = canceledAt;
    }
}
