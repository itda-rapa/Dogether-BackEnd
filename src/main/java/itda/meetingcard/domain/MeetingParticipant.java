package itda.meetingcard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
 * 약속 카드 참여 Pet. M1 은 DIRECT 방이라 카드당 정확히 두 행이다.
 *
 * <p>참여자를 별도 테이블로 두는 이유는 조회·취소 권한을 방 참가자가 아니라 카드 참여자
 * 기준으로 판단해야 하기 때문이다. 방을 떠난 Pet 도 자기가 참여한 카드는 볼 수 있어야 한다.
 */
@Getter
@Entity
@Table(name = "meeting_participants")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeetingParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meeting_card_id", nullable = false)
    private Long meetingCardId;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public MeetingParticipant(Long meetingCardId, Long petId) {
        this.meetingCardId = meetingCardId;
        this.petId = petId;
    }
}
