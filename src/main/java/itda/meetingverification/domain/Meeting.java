package itda.meetingverification.domain;

import itda.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * GPS 또는 Code 방식으로 실제 만남이 확정된 최종 정본. 확정 시점에만 생성되며,
 * 같은 약속 카드로 두 번 만들어지지 않는다({@code uk_meeting_card}).
 *
 * <p>미확정 상태(NOT_SUBMITTED/WAITING_COUNTERPART/...)를 저장하지 않는다. 제출·대기
 * 상태는 {@link MeetingVerification} 행의 존재와 양쪽 제출 여부로 표현한다.
 *
 * <p>GPS 확정은 실제 계산 거리({@code distanceMeters})를 저장하고, CODE 확정은 거리값이
 * 없어 {@code null} 이다. DB 의 {@code ck_meeting_distance_by_method} 가 GPS 는 non-null,
 * CODE 는 null 을 강제한다. 이후 #149 가 CODE Meeting 을 만들 때 {@code distanceMeters}
 * {@code null} 로 생성해야 한다.
 */
@Getter
@Entity
@Table(name = "meetings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Meeting extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meeting_card_id", nullable = false)
    private Long meetingCardId;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_method", nullable = false, length = 20)
    private MeetingVerificationMethod verificationMethod;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    @Column(name = "distance_meters")
    private Double distanceMeters;

    public Meeting(Long meetingCardId,
                   MeetingVerificationMethod verificationMethod,
                   Instant confirmedAt,
                   Double distanceMeters) {
        this.meetingCardId = meetingCardId;
        this.verificationMethod = verificationMethod;
        this.confirmedAt = confirmedAt;
        this.distanceMeters = distanceMeters;
    }
}
