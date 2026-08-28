package itda.meetingcard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * AI 가 추출한 약속 초안. 확정 전 단계이므로 종류·장소·시각이 모두 nullable 이다.
 *
 * <p>Pet·Room 은 scalar id 로 들고 있다. Chat·Report 와 같은 관행이며 응답 조립 때 행마다
 * 프록시가 로딩되는 것을 피한다.
 */
@Getter
@Entity
@Table(name = "card_drafts")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CardDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "requested_by_pet_id", nullable = false)
    private Long requestedByPetId;

    /** AI 가 종류를 못 뽑으면 null 이고, 사용자가 폼에서 직접 고른다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "card_type", length = 20)
    private MeetingCardType cardType;

    @Column(name = "place_text", length = 500)
    private String placeText;

    @Column(name = "meet_at")
    private Instant meetAt;

    /** AI가 추출한 날짜 부분. 시각만 추출된 경우에도 다른 부분값과 독립적으로 보존한다. */
    @Column(name = "extracted_date", length = 10)
    private String date;

    /** AI가 추출한 시각 부분(HH:mm). 날짜가 없어 meetAt을 만들 수 없어도 보존한다. */
    @Column(name = "extracted_time", length = 5)
    private String time;

    @Enumerated(EnumType.STRING)
    @Column(name = "fallback_reason", length = 30)
    private CardDraftFallbackReason fallbackReason;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "candidate_index")
    private Integer candidateIndex;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public CardDraft(Long roomId,
                     Long requestedByPetId,
                     MeetingCardType cardType,
                     String placeText,
                     Instant meetAt,
                     String date,
                     String time,
                     CardDraftFallbackReason fallbackReason) {
        this.roomId = roomId;
        this.requestedByPetId = requestedByPetId;
        this.cardType = cardType;
        this.placeText = placeText;
        this.meetAt = meetAt;
        this.date = date;
        this.time = time;
        this.fallbackReason = fallbackReason;
    }

    public CardDraft(Long roomId,
                     Long requestedByPetId,
                     MeetingCardType cardType,
                     String placeText,
                     Instant meetAt,
                     String date,
                     String time,
                     CardDraftFallbackReason fallbackReason,
                     String requestId,
                     Integer candidateIndex) {
        this(roomId, requestedByPetId, cardType, placeText, meetAt, date, time, fallbackReason);
        this.requestId = requestId;
        this.candidateIndex = candidateIndex;
    }

    /**
     * 응답의 {@code fallback} 플래그. 컬럼이 아니라 파생값이다.
     *
     * <p>AI 가 정상 동작했으나 약속을 못 찾은 경우와 일부만 추출한 경우는 fallback 이
     * 아니므로 사유가 null 이고, 따라서 이 값도 false 다.
     */
    public boolean isFallback() {
        return fallbackReason != null;
    }
}
