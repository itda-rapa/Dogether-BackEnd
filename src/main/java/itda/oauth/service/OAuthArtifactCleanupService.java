package itda.oauth.service;

import itda.oauth.repository.OAuthLoginCodeRepository;
import itda.oauth.repository.OAuthSignupTokenRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes only after a short physical-retention grace so exchange/signup can still distinguish a
 * logically expired artifact from an invalid one.
 */
@Service
public class OAuthArtifactCleanupService {

    static final Duration PHYSICAL_CLEANUP_GRACE = Duration.ofMinutes(1);

    private final OAuthLoginCodeRepository loginCodeRepository;
    private final OAuthSignupTokenRepository signupTokenRepository;
    private final Clock clock;

    @Autowired
    public OAuthArtifactCleanupService(
            OAuthLoginCodeRepository loginCodeRepository,
            OAuthSignupTokenRepository signupTokenRepository
    ) {
        this(loginCodeRepository, signupTokenRepository, Clock.systemUTC());
    }

    OAuthArtifactCleanupService(
            OAuthLoginCodeRepository loginCodeRepository,
            OAuthSignupTokenRepository signupTokenRepository,
            Clock clock
    ) {
        this.loginCodeRepository = loginCodeRepository;
        this.signupTokenRepository = signupTokenRepository;
        this.clock = clock;
    }

    @Transactional
    public int deleteExpiredArtifacts() {
        // Artifact use checks expiresAt directly. Retain the row one extra minute so the API can
        // return EXPIRED rather than INVALID during that grace period.
        java.time.Instant physicalCleanupCutoff = clock.instant().minus(PHYSICAL_CLEANUP_GRACE);
        return loginCodeRepository.deleteExpiredBefore(physicalCleanupCutoff)
                + signupTokenRepository.deleteExpiredBefore(physicalCleanupCutoff);
    }
}
