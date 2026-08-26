package itda.meetingverification.domain;

import itda.common.BaseEntity;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.meetingverification.service.ConfirmationCodeStatePersistException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 카드당 현재 Confirmation Code 한 건. 평문 코드는 이 엔티티에 절대 저장하지 않는다.
 * 재발급은 같은 행의 hash와 확인 흔적을 교체해 이전 코드를 즉시 무효화한다.
 */
@Getter
@Entity
@Table(name = "meeting_confirmation_codes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeetingConfirmationCode extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meeting_card_id", nullable = false, unique = true)
    private Long meetingCardId;

    @Column(name = "issuer_pet_id", nullable = false)
    private Long issuerPetId;

    @Column(name = "code_hash", nullable = false, length = 255)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "verifier_pet_id")
    private Long verifierPetId;

    @Column(name = "verifier_confirmed_at")
    private Instant verifierConfirmedAt;

    @Column(name = "issuer_confirmed_at")
    private Instant issuerConfirmedAt;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    public MeetingConfirmationCode(Long meetingCardId, Long issuerPetId, String codeHash,
                                   Instant expiresAt) {
        this.meetingCardId = meetingCardId;
        this.issuerPetId = issuerPetId;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
    }

    /** 최초 issuer를 보존하며 같은 issuer의 새 cycle로만 교체한다. */
    public void reissue(String codeHash, Instant expiresAt) {
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.failedAttempts = 0;
        this.verifierPetId = null;
        this.verifierConfirmedAt = null;
        this.issuerConfirmedAt = null;
        this.invalidatedAt = null;
    }

    public void requireUsable(Instant now) {
        if (invalidatedAt != null) {
            throw new BusinessException(ErrorCode.MEETING_CODE_NOT_AVAILABLE);
        }
        if (!expiresAt.isAfter(now)) {
            invalidatedAt = now;
            throw new ConfirmationCodeStatePersistException(ErrorCode.MEETING_CODE_EXPIRED);
        }
    }

    /** 만료·무효화 없이 아직 사용 가능한지 확인한다(상태를 변경하지 않는다). */
    public boolean isUsableAt(Instant now) {
        return invalidatedAt == null && expiresAt.isAfter(now);
    }

    /** Call only while the code row is locked. */
    public void recordFailedAttempt(Instant now, int maxAttempts) {
        failedAttempts++;
        if (failedAttempts >= maxAttempts) {
            invalidatedAt = now;
            throw new ConfirmationCodeStatePersistException(ErrorCode.MEETING_CODE_ATTEMPTS_EXCEEDED);
        }
        throw new ConfirmationCodeStatePersistException(ErrorCode.MEETING_CODE_MISMATCH);
    }

    public void verifyBy(Long verifierPetId, Instant now) {
        if (this.verifierPetId == null) {
            this.verifierPetId = verifierPetId;
            this.verifierConfirmedAt = now;
            return;
        }
        if (!this.verifierPetId.equals(verifierPetId)) {
            throw new BusinessException(ErrorCode.MEETING_CODE_ISSUER_FORBIDDEN);
        }
    }

    public void confirmByIssuer(Instant now) {
        if (verifierConfirmedAt == null) {
            throw new BusinessException(ErrorCode.MEETING_CODE_VERIFIER_REQUIRED);
        }
        if (issuerConfirmedAt == null) {
            issuerConfirmedAt = now;
        }
    }
}
