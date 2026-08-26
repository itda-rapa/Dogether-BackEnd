package itda.auth.service;

import itda.auth.dto.AuthTokensResponse;
import itda.auth.dto.LoginRequest;
import itda.auth.dto.SignupRequest;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.security.service.TokenProvider;
import itda.email.EmailVerificationPurpose;
import itda.email.EmailVerificationService;
import itda.neighborhood.repository.NeighborhoodRepository;
import itda.oauth.domain.OAuthProvider;
import itda.oauth.service.OAuthExchangeCommand;
import itda.oauth.service.OAuthExchangeResult;
import itda.oauth.service.OAuthExchangeService;
import itda.oauth.service.OAuthFlowException;
import itda.oauth.service.OAuthFlowFailure;
import itda.oauth.service.OAuthSignupCommand;
import itda.oauth.service.OAuthSignupService;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import itda.user.service.PublicTagGenerator;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final int PUBLIC_TAG_SAVE_ATTEMPTS = 5;
    private static final String EMAIL_UNIQUE_CONSTRAINT = "uk_users_email_lower";
    private static final String PUBLIC_TAG_UNIQUE_CONSTRAINT = "uk_users_public_tag";

    private final UserRepository userRepository;
    private final NeighborhoodRepository neighborhoodRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenProvider tokenProvider;
    private final PublicTagGenerator publicTagGenerator;
    private final UserRegistrationService userRegistrationService;
    private final EmailVerificationService emailVerificationService;
    private final OAuthExchangeService oauthExchangeService;
    private final OAuthSignupService oauthSignupService;

    @Autowired
    public AuthService(
            UserRepository userRepository,
            NeighborhoodRepository neighborhoodRepository,
            PasswordEncoder passwordEncoder,
            TokenProvider tokenProvider,
            PublicTagGenerator publicTagGenerator,
            UserRegistrationService userRegistrationService,
            EmailVerificationService emailVerificationService,
            OAuthExchangeService oauthExchangeService,
            OAuthSignupService oauthSignupService
    ) {
        this.userRepository = userRepository;
        this.neighborhoodRepository = neighborhoodRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.publicTagGenerator = publicTagGenerator;
        this.userRegistrationService = userRegistrationService;
        this.emailVerificationService = emailVerificationService;
        this.oauthExchangeService = oauthExchangeService;
        this.oauthSignupService = oauthSignupService;
    }

    /** Retained for existing unit tests that do not exercise OAuth flows. */
    public AuthService(
            UserRepository userRepository,
            NeighborhoodRepository neighborhoodRepository,
            PasswordEncoder passwordEncoder,
            TokenProvider tokenProvider,
            PublicTagGenerator publicTagGenerator,
            UserRegistrationService userRegistrationService,
            EmailVerificationService emailVerificationService
    ) {
        this(
                userRepository,
                neighborhoodRepository,
                passwordEncoder,
                tokenProvider,
                publicTagGenerator,
                userRegistrationService,
                emailVerificationService,
                null,
                null
        );
    }

    public AuthTokensResponse signup(SignupRequest request) {
        String email = normalizeEmail(request.email());

        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new BusinessException(ErrorCode.USER_EMAIL_DUPLICATED);
        }
        if (!neighborhoodRepository.existsByCodeAndActiveTrue(request.neighborhoodCode())) {
            throw new BusinessException(ErrorCode.NEIGHBORHOOD_NOT_FOUND);
        }

        emailVerificationService.consume(
                request.verificationToken(), email, EmailVerificationPurpose.SIGNUP
        );

        String passwordHash = passwordEncoder.encode(request.password());
        for (int attempt = 0; attempt < PUBLIC_TAG_SAVE_ATTEMPTS; attempt++) {
            User user = User.register(
                    email,
                    passwordHash,
                    request.nickname().trim(),
                    publicTagGenerator.generate(request.nickname()),
                    request.neighborhoodCode()
            );

            try {
                return userRegistrationService.registerAndIssue(user);
            } catch (DataIntegrityViolationException exception) {
                if (isConstraintViolation(exception, EMAIL_UNIQUE_CONSTRAINT)) {
                    throw new BusinessException(ErrorCode.USER_EMAIL_DUPLICATED);
                }
                if (!isConstraintViolation(
                        exception,
                        PUBLIC_TAG_UNIQUE_CONSTRAINT
                )) {
                    throw exception;
                }
            }
        }

        throw new BusinessException(ErrorCode.PUBLIC_TAG_GENERATION_FAILED);
    }

    @Transactional
    public AuthTokensResponse login(LoginRequest request) {
        User user = userRepository.findByEmailIgnoreCase(normalizeEmail(request.email()))
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));

        if (!user.hasPasswordCredential()
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }

        return AuthTokensResponse.from(tokenProvider.issueTokens(user));
    }

    public AuthTokensResponse refresh(String rawRefreshToken) {
        return AuthTokensResponse.from(
                tokenProvider.rotateRefreshToken(rawRefreshToken)
        );
    }

    public OAuthExchangeResult<AuthTokensResponse> exchangeOAuth(
            OAuthProvider provider,
            String loginCode
    ) {
        if (provider != OAuthProvider.GOOGLE) {
            throw new BusinessException(ErrorCode.OAUTH_PROVIDER_UNSUPPORTED);
        }
        try {
            return oauthExchangeService.exchange(
                    new OAuthExchangeCommand(provider, loginCode),
                    user -> AuthTokensResponse.from(tokenProvider.issueTokens(user))
            );
        } catch (OAuthFlowException exception) {
            throw oauthFailure(exception);
        }
    }

    public AuthTokensResponse signupOAuth(
            String signupToken,
            String nickname,
            String neighborhoodCode
    ) {
        try {
            return oauthSignupService.complete(
                    new OAuthSignupCommand(signupToken, nickname, neighborhoodCode),
                    user -> AuthTokensResponse.from(tokenProvider.issueTokens(user))
            );
        } catch (OAuthFlowException exception) {
            throw oauthFailure(exception);
        }
    }

    @Transactional
    public void logout(Long userId) {
        tokenProvider.revokeAllForUser(userId);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private BusinessException oauthFailure(OAuthFlowException exception) {
        ErrorCode errorCode = switch (exception.getFailure()) {
            case LOGIN_CODE_INVALID -> ErrorCode.OAUTH_LOGIN_CODE_INVALID;
            case LOGIN_CODE_EXPIRED -> ErrorCode.OAUTH_LOGIN_CODE_EXPIRED;
            case LOGIN_CODE_CONSUMED -> ErrorCode.OAUTH_LOGIN_CODE_CONSUMED;
            case SIGNUP_TOKEN_INVALID -> ErrorCode.OAUTH_SIGNUP_TOKEN_INVALID;
            case SIGNUP_TOKEN_EXPIRED -> ErrorCode.OAUTH_SIGNUP_TOKEN_EXPIRED;
            case ACCOUNT_LINK_DECISION_REQUIRED ->
                    ErrorCode.OAUTH_ACCOUNT_LINK_DECISION_REQUIRED;
            case ACCOUNT_NOT_ACTIVE -> ErrorCode.ACCOUNT_NOT_ACTIVE;
            case VALIDATION_FAILED -> ErrorCode.VALIDATION_FAILED;
            case NEIGHBORHOOD_NOT_FOUND -> ErrorCode.NEIGHBORHOOD_NOT_FOUND;
            case CONCURRENT_UPDATE_CONFLICT -> ErrorCode.CONCURRENT_UPDATE_CONFLICT;
            case PUBLIC_TAG_GENERATION_FAILED -> ErrorCode.PUBLIC_TAG_GENERATION_FAILED;
        };
        return new BusinessException(errorCode);
    }

    private boolean isConstraintViolation(
            Throwable throwable,
            String expectedConstraint
    ) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException
                    constraintViolation
                    && expectedConstraint.equalsIgnoreCase(
                    constraintViolation.getConstraintName()
            )) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT)
                    .contains(expectedConstraint.toLowerCase(Locale.ROOT))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
