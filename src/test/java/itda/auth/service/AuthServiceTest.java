package itda.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import itda.auth.dto.LoginRequest;
import itda.auth.dto.SignupRequest;
import itda.auth.dto.AuthTokensResponse;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.security.service.TokenProvider;
import itda.neighborhood.repository.NeighborhoodRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import itda.user.service.PublicTagGenerator;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private NeighborhoodRepository neighborhoodRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private TokenProvider tokenProvider;
    @Mock
    private PublicTagGenerator publicTagGenerator;
    @Mock
    private UserRegistrationService userRegistrationService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                neighborhoodRepository,
                passwordEncoder,
                tokenProvider,
                publicTagGenerator,
                userRegistrationService
        );
    }

    @Test
    void signupChecksNeighborhoodAndStoresEncodedPassword() {
        SignupRequest request = new SignupRequest(
                "USER@example.com",
                "long-password",
                "사용자",
                "1168010100"
        );
        given(userRepository.findByEmailIgnoreCase("user@example.com"))
                .willReturn(Optional.empty());
        given(neighborhoodRepository.existsByCodeAndActiveTrue("1168010100"))
                .willReturn(true);
        given(passwordEncoder.encode("long-password")).willReturn("encoded");
        given(publicTagGenerator.generate("사용자")).willReturn("사용자#A7K2");
        given(userRegistrationService.registerAndIssue(any(User.class)))
                .willReturn(new AuthTokensResponse(
                        "access",
                        "refresh",
                        Instant.parse("2026-07-24T00:30:00Z")
                ));

        authService.signup(request);

        verify(passwordEncoder).encode("long-password");
        verify(userRegistrationService).registerAndIssue(any(User.class));
    }

    @Test
    void loginDoesNotRevealWhetherEmailOrPasswordWasWrong() {
        given(userRepository.findByEmailIgnoreCase("missing@example.com"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("missing@example.com", "wrong-password")
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.LOGIN_FAILED);
    }

    @Test
    void signupRetriesAfterPublicTagUniqueCollision() {
        SignupRequest request = new SignupRequest(
                "user@example.com",
                "long-password",
                "사용자",
                "4113111500"
        );
        given(userRepository.findByEmailIgnoreCase("user@example.com"))
                .willReturn(Optional.empty());
        given(neighborhoodRepository.existsByCodeAndActiveTrue("4113111500"))
                .willReturn(true);
        given(passwordEncoder.encode("long-password")).willReturn("encoded");
        given(publicTagGenerator.generate("사용자"))
                .willReturn("사용자#AAAA", "사용자#BBBB");
        given(userRegistrationService.registerAndIssue(any(User.class)))
                .willThrow(new DataIntegrityViolationException(
                        "duplicate key violates uk_users_public_tag"
                ))
                .willReturn(new AuthTokensResponse(
                        "access",
                        "refresh",
                        Instant.parse("2026-07-24T00:30:00Z")
                ));

        authService.signup(request);

        verify(userRegistrationService, times(2))
                .registerAndIssue(any(User.class));
        verify(publicTagGenerator, times(2)).generate("사용자");
    }
}
