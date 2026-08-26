package itda.oauth.service;

import itda.common.security.TokenHashing;
import itda.oauth.domain.OAuthIdentity;
import itda.oauth.domain.OAuthLoginCode;
import itda.oauth.domain.OAuthSignupToken;
import itda.oauth.repository.OAuthIdentityRepository;
import itda.oauth.repository.OAuthLoginCodeRepository;
import itda.oauth.repository.OAuthSignupTokenRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OAuthExchangeService {

    static final Duration SIGNUP_TOKEN_TTL = Duration.ofMinutes(10);

    private final OAuthLoginCodeRepository loginCodeRepository;
    private final OAuthSignupTokenRepository signupTokenRepository;
    private final OAuthIdentityRepository identityRepository;
    private final UserRepository userRepository;
    private final OAuthOpaqueTokenGenerator tokenGenerator;
    private final Clock clock;

    @Autowired
    public OAuthExchangeService(
            OAuthLoginCodeRepository loginCodeRepository,
            OAuthSignupTokenRepository signupTokenRepository,
            OAuthIdentityRepository identityRepository,
            UserRepository userRepository,
            OAuthOpaqueTokenGenerator tokenGenerator
    ) {
        this(
                loginCodeRepository,
                signupTokenRepository,
                identityRepository,
                userRepository,
                tokenGenerator,
                Clock.systemUTC()
        );
    }

    OAuthExchangeService(
            OAuthLoginCodeRepository loginCodeRepository,
            OAuthSignupTokenRepository signupTokenRepository,
            OAuthIdentityRepository identityRepository,
            UserRepository userRepository,
            OAuthOpaqueTokenGenerator tokenGenerator,
            Clock clock
    ) {
        this.loginCodeRepository = loginCodeRepository;
        this.signupTokenRepository = signupTokenRepository;
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
        this.tokenGenerator = tokenGenerator;
        this.clock = clock;
    }

    /**
     * Exchanges a code only after the caller-provided completion has successfully issued Dogether
     * tokens. A same-email, unlinked account deliberately leaves the code AVAILABLE.
     */
    @Transactional
    public <T> OAuthExchangeResult<T> exchange(
            OAuthExchangeCommand command,
            OAuthAuthenticatedUserCompletion<T> existingUserCompletion
    ) {
        OAuthLoginCode loginCode = loginCodeRepository.findByTokenHashForUpdate(
                        TokenHashing.sha256(command.loginCode()))
                .orElseThrow(() -> failure(OAuthFlowFailure.LOGIN_CODE_INVALID));
        Instant now = clock.instant();
        validateLoginCode(command, loginCode, now);

        OAuthIdentity identity = identityRepository
                .findWithUserByProviderAndProviderSubject(
                        loginCode.getProvider(), loginCode.getProviderSubject()
                )
                .orElse(null);
        if (identity != null) {
            User user = identity.getUser();
            if (!user.isActive()) {
                throw failure(OAuthFlowFailure.ACCOUNT_NOT_ACTIVE);
            }
            loginCode.consumeAndScrub(now);
            return new OAuthExchangeResult.ExistingUser<>(existingUserCompletion.complete(user));
        }

        String verifiedEmail = loginCode.getVerifiedEmail();
        if (verifiedEmail == null) {
            throw failure(OAuthFlowFailure.LOGIN_CODE_INVALID);
        }
        if (userRepository.findByEmailIgnoreCase(verifiedEmail).isPresent()) {
            throw failure(OAuthFlowFailure.ACCOUNT_LINK_DECISION_REQUIRED);
        }

        String rawSignupToken = tokenGenerator.generate();
        Instant signupTokenExpiresAt = now.plus(SIGNUP_TOKEN_TTL);
        signupTokenRepository.save(OAuthSignupToken.issue(
                TokenHashing.sha256(rawSignupToken),
                loginCode.getProvider(),
                loginCode.getProviderSubject(),
                verifiedEmail,
                signupTokenExpiresAt
        ));
        loginCode.consumeAndScrub(now);
        return new OAuthExchangeResult.SignupRequired<>(rawSignupToken, signupTokenExpiresAt);
    }

    private void validateLoginCode(
            OAuthExchangeCommand command,
            OAuthLoginCode loginCode,
            Instant now
    ) {
        if (loginCode.getProvider() != command.provider()) {
            throw failure(OAuthFlowFailure.LOGIN_CODE_INVALID);
        }
        if (loginCode.isExpiredAt(now)) {
            throw failure(OAuthFlowFailure.LOGIN_CODE_EXPIRED);
        }
        if (!loginCode.isAvailable()) {
            throw failure(OAuthFlowFailure.LOGIN_CODE_CONSUMED);
        }
    }

    private OAuthFlowException failure(OAuthFlowFailure failure) {
        return new OAuthFlowException(failure);
    }
}
