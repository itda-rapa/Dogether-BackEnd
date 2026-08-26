package itda.oauth.service;

import itda.common.security.TokenHashing;
import itda.oauth.domain.OAuthLoginCode;
import itda.oauth.repository.OAuthLoginCodeRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OAuthLoginCodeIssuer {

    static final Duration LOGIN_CODE_TTL = Duration.ofMinutes(5);

    private final OAuthLoginCodeRepository loginCodeRepository;
    private final OAuthOpaqueTokenGenerator tokenGenerator;
    private final Clock clock;

    @Autowired
    public OAuthLoginCodeIssuer(
            OAuthLoginCodeRepository loginCodeRepository,
            OAuthOpaqueTokenGenerator tokenGenerator
    ) {
        this(loginCodeRepository, tokenGenerator, Clock.systemUTC());
    }

    OAuthLoginCodeIssuer(
            OAuthLoginCodeRepository loginCodeRepository,
            OAuthOpaqueTokenGenerator tokenGenerator,
            Clock clock
    ) {
        this.loginCodeRepository = loginCodeRepository;
        this.tokenGenerator = tokenGenerator;
        this.clock = clock;
    }

    @Transactional
    public IssuedOAuthLoginCode issue(OAuthVerifiedIdentity identity) {
        Instant now = clock.instant();
        String rawLoginCode = tokenGenerator.generate();
        Instant expiresAt = now.plus(LOGIN_CODE_TTL);
        loginCodeRepository.save(OAuthLoginCode.issue(
                TokenHashing.sha256(rawLoginCode),
                identity.provider(),
                identity.providerSubject(),
                identity.verifiedEmail(),
                expiresAt
        ));
        return new IssuedOAuthLoginCode(rawLoginCode, expiresAt);
    }
}
