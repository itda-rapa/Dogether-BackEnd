package itda.oauth.domain;

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

@Getter
@Entity
@Table(name = "oauth_signup_tokens")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OAuthSignupToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OAuthProvider provider;

    @Column(name = "provider_subject", nullable = false, length = 255)
    private String providerSubject;

    @Column(name = "verified_email", length = 320)
    private String verifiedEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OAuthArtifactStatus status = OAuthArtifactStatus.AVAILABLE;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    private OAuthSignupToken(
            String tokenHash,
            OAuthProvider provider,
            String providerSubject,
            String verifiedEmail,
            Instant expiresAt
    ) {
        this.tokenHash = tokenHash;
        this.provider = provider;
        this.providerSubject = providerSubject;
        this.verifiedEmail = verifiedEmail;
        this.expiresAt = expiresAt;
    }

    public static OAuthSignupToken issue(
            String tokenHash,
            OAuthProvider provider,
            String providerSubject,
            String verifiedEmail,
            Instant expiresAt
    ) {
        return new OAuthSignupToken(
                tokenHash,
                provider,
                providerSubject,
                verifiedEmail,
                expiresAt
        );
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isAvailable() {
        return status == OAuthArtifactStatus.AVAILABLE;
    }

    public void consumeAndScrub(Instant now) {
        if (!isAvailable()) {
            throw new IllegalStateException("OAuth signup token is already consumed.");
        }
        status = OAuthArtifactStatus.CONSUMED;
        consumedAt = now;
        verifiedEmail = null;
    }
}
