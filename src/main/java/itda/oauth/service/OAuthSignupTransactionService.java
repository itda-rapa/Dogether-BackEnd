package itda.oauth.service;

import itda.common.security.TokenHashing;
import itda.neighborhood.repository.NeighborhoodRepository;
import itda.oauth.domain.OAuthIdentity;
import itda.oauth.domain.OAuthSignupToken;
import itda.oauth.repository.OAuthIdentityRepository;
import itda.oauth.repository.OAuthSignupTokenRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OAuthSignupTransactionService {

    private static final BigDecimal MIN_WEIGHT_KG = new BigDecimal("1.00");
    private static final BigDecimal MAX_WEIGHT_KG = new BigDecimal("500.00");

    private final OAuthSignupTokenRepository signupTokenRepository;
    private final OAuthIdentityRepository identityRepository;
    private final UserRepository userRepository;
    private final NeighborhoodRepository neighborhoodRepository;
    private final Clock clock;

    @Autowired
    public OAuthSignupTransactionService(
            OAuthSignupTokenRepository signupTokenRepository,
            OAuthIdentityRepository identityRepository,
            UserRepository userRepository,
            NeighborhoodRepository neighborhoodRepository
    ) {
        this(
                signupTokenRepository,
                identityRepository,
                userRepository,
                neighborhoodRepository,
                Clock.systemUTC()
        );
    }

    OAuthSignupTransactionService(
            OAuthSignupTokenRepository signupTokenRepository,
            OAuthIdentityRepository identityRepository,
            UserRepository userRepository,
            NeighborhoodRepository neighborhoodRepository,
            Clock clock
    ) {
        this.signupTokenRepository = signupTokenRepository;
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
        this.neighborhoodRepository = neighborhoodRepository;
        this.clock = clock;
    }

    /**
     * A separate transaction is intentional: a public-tag unique collision rolls back every
     * signup side effect before the outer service generates a fresh tag and retries.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T completeAttempt(
            OAuthSignupCommand command,
            String publicTag,
            OAuthSignupCompletion<T> completion
    ) {
        OAuthSignupToken signupToken = signupTokenRepository.findByTokenHashForUpdate(
                        TokenHashing.sha256(required(command.signupToken())))
                .orElseThrow(() -> failure(OAuthFlowFailure.SIGNUP_TOKEN_INVALID));
        Instant now = clock.instant();
        validateSignupToken(signupToken, now);

        String nickname = normalizeNickname(command.nickname());
        String neighborhoodCode = normalizeNeighborhoodCode(command.neighborhoodCode());
        validateWeightKg(command.weightKg());
        if (!neighborhoodRepository.existsByCodeAndActiveTrue(neighborhoodCode)) {
            throw failure(OAuthFlowFailure.NEIGHBORHOOD_NOT_FOUND);
        }

        String verifiedEmail = signupToken.getVerifiedEmail();
        if (verifiedEmail == null
                || userRepository.findByEmailIgnoreCase(verifiedEmail).isPresent()
                || identityRepository.existsByProviderAndProviderSubject(
                signupToken.getProvider(), signupToken.getProviderSubject())) {
            throw failure(OAuthFlowFailure.CONCURRENT_UPDATE_CONFLICT);
        }

        User user = User.registerOAuth(
                verifiedEmail,
                nickname,
                publicTag,
                neighborhoodCode,
                command.weightKg()
        );
        user = userRepository.saveAndFlush(user);
        identityRepository.saveAndFlush(OAuthIdentity.link(
                user,
                signupToken.getProvider(),
                signupToken.getProviderSubject()
        ));
        signupToken.consumeAndScrub(now);
        return completion.complete(user);
    }

    private void validateSignupToken(OAuthSignupToken signupToken, Instant now) {
        if (!signupToken.isAvailable()) {
            throw failure(OAuthFlowFailure.SIGNUP_TOKEN_INVALID);
        }
        if (signupToken.isExpiredAt(now)) {
            throw failure(OAuthFlowFailure.SIGNUP_TOKEN_EXPIRED);
        }
    }

    private String normalizeNickname(String rawNickname) {
        if (rawNickname == null) {
            throw failure(OAuthFlowFailure.VALIDATION_FAILED);
        }
        String nickname = rawNickname.trim();
        if (nickname.length() < 2 || nickname.length() > 20) {
            throw failure(OAuthFlowFailure.VALIDATION_FAILED);
        }
        return nickname;
    }

    private String normalizeNeighborhoodCode(String rawNeighborhoodCode) {
        if (rawNeighborhoodCode == null) {
            throw failure(OAuthFlowFailure.VALIDATION_FAILED);
        }
        String neighborhoodCode = rawNeighborhoodCode.trim();
        if (neighborhoodCode.isEmpty() || neighborhoodCode.length() > 20) {
            throw failure(OAuthFlowFailure.VALIDATION_FAILED);
        }
        return neighborhoodCode;
    }

    private void validateWeightKg(BigDecimal weightKg) {
        if (weightKg == null) {
            return;
        }
        if (weightKg.compareTo(MIN_WEIGHT_KG) < 0
                || weightKg.compareTo(MAX_WEIGHT_KG) > 0
                || weightKg.scale() > 2) {
            throw failure(OAuthFlowFailure.VALIDATION_FAILED);
        }
    }

    private String required(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw failure(OAuthFlowFailure.SIGNUP_TOKEN_INVALID);
        }
        return rawToken;
    }

    private OAuthFlowException failure(OAuthFlowFailure failure) {
        return new OAuthFlowException(failure);
    }
}
