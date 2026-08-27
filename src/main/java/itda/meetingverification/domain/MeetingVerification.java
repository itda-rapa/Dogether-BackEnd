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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Pet 한 명의 최신 위치 제출(01_M3_통합_ERD.md §6 {@code meeting_verifications}).
 * {@code (meeting_card_id, participant_pet_id)} 당 1건이고 같은 Pet 의 새 제출이 이전 제출을
 * 대체한다.
 *
 * <p>raw 위치(latitude/longitude/accuracy_meters/captured_at)는 GPS 판정에 필요한
 * {@code SUBMITTED} 상태에서만 보관하고, {@code CODE_REQUIRED}/{@code ACCEPTED}/
 * {@code REJECTED}/{@code EXPIRED} 전환 시 scrub(null)한다. {@code submitted_at} 은 서버
 * 수신시각으로 양쪽 제출 간격 정책의 정본이고 {@code captured_at} 은 GPS 측위시각으로
 * freshness·위치 신뢰도 판정에만 쓴다.
 *
 * <p>{@code client_request_id} 는 최신 제출이 사용한 멱등키일 뿐, 동일 HMAC key 유지 동안의
 * replay 정본은 {@link MeetingVerificationRequest} ledger 다.
 */
@Getter
@Entity
@Table(name = "meeting_verifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeetingVerification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meeting_card_id", nullable = false)
    private Long meetingCardId;

    @Column(name = "participant_pet_id", nullable = false)
    private Long participantPetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private MeetingVerificationStatus status;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "accuracy_meters")
    private Double accuracyMeters;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "client_request_id", nullable = false)
    private UUID clientRequestId;

    public MeetingVerification(Long meetingCardId,
                               Long participantPetId,
                               UUID clientRequestId,
                               Double latitude,
                               Double longitude,
                               Double accuracyMeters,
                               Instant capturedAt,
                               Instant submittedAt,
                               MeetingVerificationStatus status) {
        this.meetingCardId = meetingCardId;
        this.participantPetId = participantPetId;
        this.clientRequestId = clientRequestId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyMeters = accuracyMeters;
        this.capturedAt = capturedAt;
        this.submittedAt = submittedAt;
        this.status = status;
    }

    /**
     * 같은 Pet 이 새 {@code clientRequestId} 로 다시 제출하면 최신 제출이 이전 제출을
     * 대체한다. {@code submittedAt} 도 최신 제출의 서버 수신시각으로 갱신한다.
     */
    public void replace(UUID clientRequestId,
                        Double latitude,
                        Double longitude,
                        Double accuracyMeters,
                        Instant capturedAt,
                        Instant submittedAt,
                        MeetingVerificationStatus status) {
        this.clientRequestId = clientRequestId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyMeters = accuracyMeters;
        this.capturedAt = capturedAt;
        this.submittedAt = submittedAt;
        this.status = status;
    }

    /** GPS 확정 시 양쪽 제출을 ACCEPTED 로 전이하고 raw 위치를 scrub 한다. */
    public void accept() {
        this.status = MeetingVerificationStatus.ACCEPTED;
        scrubRawLocation();
    }

    /** 약속 시간창 경과 시 SUBMITTED 를 EXPIRED 로 전이하고 raw 위치를 scrub 한다. */
    public void expire() {
        this.status = MeetingVerificationStatus.EXPIRED;
        scrubRawLocation();
    }

    /** 원문 위치 보관 종료. 로그·응답에 위경도가 노출되지 않도록 null 로 지운다. */
    public void scrubRawLocation() {
        this.latitude = null;
        this.longitude = null;
        this.accuracyMeters = null;
        this.capturedAt = null;
    }
}
