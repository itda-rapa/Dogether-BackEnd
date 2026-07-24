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
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final NeighborhoodRepository neighborhoodRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenProvider tokenProvider;

    public AuthService(
            UserRepository userRepository,
            NeighborhoodRepository neighborhoodRepository,
            PasswordEncoder passwordEncoder,
            TokenProvider tokenProvider
    ) {
        this.userRepository = userRepository;
        this.neighborhoodRepository = neighborhoodRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
    }

    @Transactional
    public AuthTokensResponse signup(SignupRequest request) {
        String email = normalizeEmail(request.email());

        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new BusinessException(ErrorCode.USER_EMAIL_DUPLICATED);
        }
        if (!neighborhoodRepository.existsByCodeAndActiveTrue(request.neighborhoodCode())) {
            throw new BusinessException(ErrorCode.NEIGHBORHOOD_NOT_FOUND);
        }

        User user = User.register(
                email,
                passwordEncoder.encode(request.password()),
                request.nickname().trim(),
                request.neighborhoodCode()
        );

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.USER_EMAIL_DUPLICATED);
        }

        return AuthTokensResponse.from(tokenProvider.issueTokens(user));
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
}
