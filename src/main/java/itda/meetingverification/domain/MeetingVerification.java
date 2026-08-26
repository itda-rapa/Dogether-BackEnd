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
 * <p>status 는 Location 평가 결과에 따라 {@link MeetingVerificationService} 가 결정한다.
 * ACCEPTABLE 은 {@code SUBMITTED}, LOW_ACCURACY 는 {@code CODE_REQUIRED} 로 저장한다.
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
                               Instant capturedAt,
                               MeetingVerificationStatus status) {
        this.meetingCardId = meetingCardId;
        this.participantPetId = participantPetId;
        this.status = status;
        this.clientRequestId = clientRequestId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyMeters = accuracyMeters;
        this.capturedAt = capturedAt;
    }

    /**
     * 같은 Pet 이 새 {@code clientRequestId} 로 다시 제출하면 최신 제출이 이전 제출을
     * 대체한다. status 도 새 Location 평가 결과로 갱신한다.
     */
    public void replace(UUID clientRequestId,
                        double latitude,
                        double longitude,
                        double accuracyMeters,
                        Instant capturedAt,
                        MeetingVerificationStatus status) {
        this.clientRequestId = clientRequestId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyMeters = accuracyMeters;
        this.capturedAt = capturedAt;
        this.status = status;
    }
}
