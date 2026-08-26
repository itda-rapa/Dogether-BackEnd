package itda.oauth.service;

import itda.oauth.domain.OAuthProvider;
import java.util.Locale;

/** Values delivered only after the provider adapter has completed OIDC verification. */
public record OAuthVerifiedIdentity(
        OAuthProvider provider,
        String providerSubject,
        String verifiedEmail
) {
    public OAuthVerifiedIdentity {
        if (provider == null || providerSubject == null || providerSubject.isBlank()
                || verifiedEmail == null || verifiedEmail.isBlank()) {
            throw new IllegalArgumentException("A verified OAuth identity requires provider, subject, and email.");
        }
        providerSubject = providerSubject.trim();
        verifiedEmail = verifiedEmail.trim().toLowerCase(Locale.ROOT);
    }
}
