package itda.auth.service;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import itda.common.security.service.TokenProvider;
import itda.email.EmailVerificationPurpose;
import itda.email.EmailVerificationService;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {
    @Mock private EmailVerificationService emailVerificationService;
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TokenProvider tokenProvider;
    @Mock private User user;

    @Test
    void resetsPasswordThenRevokesAllRefreshTokens() {
        given(userRepository.findByEmailIgnoreCase("user@example.com")).willReturn(Optional.of(user));
        given(user.getId()).willReturn(7L);
        given(passwordEncoder.encode("new-password")).willReturn("new-hash");
        PasswordResetService service = new PasswordResetService(emailVerificationService, userRepository,
                passwordEncoder, tokenProvider);

        service.reset(" USER@example.com ", "verification-token", "new-password");

        InOrder order = org.mockito.Mockito.inOrder(emailVerificationService, user, tokenProvider);
        order.verify(emailVerificationService).consume("verification-token", "user@example.com",
                EmailVerificationPurpose.PASSWORD_RESET);
        order.verify(user).changePasswordHash("new-hash");
        order.verify(tokenProvider).revokeAllForUser(7L);
    }
}
