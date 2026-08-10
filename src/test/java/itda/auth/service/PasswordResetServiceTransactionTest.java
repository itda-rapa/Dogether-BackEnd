package itda.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import itda.common.security.service.TokenProvider;
import itda.email.EmailVerificationService;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.aop.support.AopUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class PasswordResetServiceTransactionTest {

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private TokenProvider tokenProvider;

    @Test
    void rollsBackPasswordChangeWhenRefreshTokenRevocationFails() {
        assertThat(AopUtils.isAopProxy(passwordResetService)).isTrue();
        String unique = UUID.randomUUID().toString().replace("-", "");
        User user = userRepository.saveAndFlush(User.register(
                unique + "@example.com", "old-password-hash", "사용자",
                "사용자#" + unique.substring(0, 8), "4113111500"
        ));
        given(passwordEncoder.encode("new-password")).willReturn("new-password-hash");
        org.mockito.Mockito.doThrow(new IllegalStateException("refresh revoke failed"))
                .when(tokenProvider).revokeAllForUser(user.getId());

        assertThatThrownBy(() -> passwordResetService.reset(
                user.getEmail(), "verification-token", "new-password"
        )).isInstanceOf(IllegalStateException.class);

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getPasswordHash()).isEqualTo("old-password-hash");
    }
}
