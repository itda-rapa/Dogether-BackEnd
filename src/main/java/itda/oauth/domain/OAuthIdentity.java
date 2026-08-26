package itda.oauth.domain;

import itda.common.BaseEntity;
import itda.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "oauth_identities",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_oauth_identities_provider_subject",
                        columnNames = {"provider", "provider_subject"}
                ),
                @UniqueConstraint(
                        name = "uk_oauth_identities_user_provider",
                        columnNames = {"user_id", "provider"}
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OAuthIdentity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OAuthProvider provider;

    @Column(name = "provider_subject", nullable = false, length = 255)
    private String providerSubject;

    private OAuthIdentity(User user, OAuthProvider provider, String providerSubject) {
        this.user = user;
        this.provider = provider;
        this.providerSubject = providerSubject;
    }

    public static OAuthIdentity link(
            User user,
            OAuthProvider provider,
            String providerSubject
    ) {
        if (user == null || provider == null || providerSubject == null
                || providerSubject.isBlank()) {
            throw new IllegalArgumentException("OAuth identity requires user, provider, and subject.");
        }
        return new OAuthIdentity(user, provider, providerSubject);
    }
}
