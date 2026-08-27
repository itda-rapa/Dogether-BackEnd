package itda.meetingverification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * immutable request 원장(동일 HMAC key 유지 동안 replay 보장, 01_M3_통합_ERD.md §6
 * {@code meeting_verification_requests}).
 *
 * <p>{@code meeting_verifications} 는 Pet 별 최신 제출 1행만 유지하고 새 제출이 이전 행을
 * 대체하므로, 과거 {@code clientRequestId} 의 멱등성은 여기에서 보존한다. 한 번 INSERT 된
 * 행은 갱신·삭제하지 않는다.
 *
 * <p>raw 좌표는 영구 보관하지 않고, 서버 비밀키 기반 HMAC-SHA-256 {@code fingerprint} 만
 * 저장한다. 같은 ID 를 다른 카드·Pet·payload 로 재사용하면 fingerprint 불일치로
 * {@code MEETING_VERIFICATION_REQUEST_CONFLICT} 로 수렴한다.
 */
@Getter
@Entity
@Table(name = "meeting_verification_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeetingVerificationRequest {

    @Id
    @Column(name = "client_request_id", nullable = false)
    private UUID clientRequestId;

    @Column(name = "meeting_card_id", nullable = false)
    private Long meetingCardId;

    @Column(name = "participant_pet_id", nullable = false)
    private Long participantPetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private MeetingVerificationStatus status;

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public MeetingVerificationRequest(UUID clientRequestId,
                                      Long meetingCardId,
                                      Long participantPetId,
                                      String fingerprint,
                                      MeetingVerificationStatus status) {
        this.clientRequestId = clientRequestId;
        this.meetingCardId = meetingCardId;
        this.participantPetId = participantPetId;
        this.fingerprint = fingerprint;
        this.status = status;
    }
}
