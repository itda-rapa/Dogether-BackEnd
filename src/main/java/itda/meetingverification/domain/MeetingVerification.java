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
 * Pet 한 명의 위치 제출(01_M3_통합_ERD.md §6 {@code meeting_verifications}).
 * {@code (meeting_card_id, participant_pet_id)} 당 1건이고 {@code client_request_id} 는
 * 전역 멱등키다.
 *
 * <p>좌표·accuracy 품질 검증과 거리·시간 판정은 #146 Location 병합 뒤 수행한다.
 * 여기서는 저장·멱등 기반만 다룬다.
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

    @Column(name = "latitude", nullable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    @Column(name = "accuracy_meters", nullable = false)
    private double accuracyMeters;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "client_request_id", nullable = false)
    private UUID clientRequestId;

    public MeetingVerification(Long meetingCardId,
                               Long participantPetId,
                               UUID clientRequestId,
                               double latitude,
                               double longitude,
                               double accuracyMeters,
                               Instant capturedAt) {
        this.meetingCardId = meetingCardId;
        this.participantPetId = participantPetId;
        this.status = MeetingVerificationStatus.SUBMITTED;
        this.clientRequestId = clientRequestId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyMeters = accuracyMeters;
        this.capturedAt = capturedAt;
    }

    /**
     * 같은 Pet 이 새 {@code clientRequestId} 로 다시 제출하면 최신 제출이 이전 제출을 대체한다.
     * 판정 전 재제출이므로 상태는 {@code SUBMITTED} 로 되돌린다.
     */
    public void replace(UUID clientRequestId,
                        double latitude,
                        double longitude,
                        double accuracyMeters,
                        Instant capturedAt) {
        this.clientRequestId = clientRequestId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyMeters = accuracyMeters;
        this.capturedAt = capturedAt;
        this.status = MeetingVerificationStatus.SUBMITTED;
    }
}
