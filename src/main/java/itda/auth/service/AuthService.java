package itda.auth.service;

import itda.auth.dto.AuthTokensResponse;
import itda.auth.dto.LoginRequest;
import itda.auth.dto.SignupRequest;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.security.service.TokenProvider;
import itda.neighborhood.repository.NeighborhoodRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import itda.user.service.PublicTagGenerator;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
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

    public AuthService(
            UserRepository userRepository,
            NeighborhoodRepository neighborhoodRepository,
            PasswordEncoder passwordEncoder,
            TokenProvider tokenProvider,
            PublicTagGenerator publicTagGenerator,
            UserRegistrationService userRegistrationService
    ) {
        this.userRepository = userRepository;
        this.neighborhoodRepository = neighborhoodRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.publicTagGenerator = publicTagGenerator;
        this.userRegistrationService = userRegistrationService;
    }

    public AuthTokensResponse signup(SignupRequest request) {
        String email = normalizeEmail(request.email());

        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new BusinessException(ErrorCode.USER_EMAIL_DUPLICATED);
        }
        if (!neighborhoodRepository.existsByCodeAndActiveTrue(request.neighborhoodCode())) {
            throw new BusinessException(ErrorCode.NEIGHBORHOOD_NOT_FOUND);
        }

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
                User registeredUser = userRegistrationService.save(user);
                return AuthTokensResponse.from(
                        tokenProvider.issueTokens(registeredUser)
                );
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

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }

        return AuthTokensResponse.from(tokenProvider.issueTokens(user));
    }

    @Transactional
    public AuthTokensResponse refresh(String rawRefreshToken) {
        return AuthTokensResponse.from(
                tokenProvider.rotateRefreshToken(rawRefreshToken)
        );
    }

    @Transactional
    public void logout(Long userId) {
        tokenProvider.revokeAllForUser(userId);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
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
