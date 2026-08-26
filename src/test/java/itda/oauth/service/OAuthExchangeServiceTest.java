package itda.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import itda.common.security.TokenHashing;
import itda.oauth.domain.OAuthArtifactStatus;
import itda.oauth.domain.OAuthIdentity;
import itda.oauth.domain.OAuthLoginCode;
import itda.oauth.domain.OAuthProvider;
import itda.oauth.domain.OAuthSignupToken;
import itda.oauth.repository.OAuthIdentityRepository;
import itda.oauth.repository.OAuthLoginCodeRepository;
import itda.oauth.repository.OAuthSignupTokenRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OAuthExchangeServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    @Mock private OAuthLoginCodeRepository loginCodeRepository;
    @Mock private OAuthSignupTokenRepository signupTokenRepository;
    @Mock private OAuthIdentityRepository identityRepository;
    @Mock private UserRepository userRepository;
    @Mock private OAuthOpaqueTokenGenerator tokenGenerator;
    @Mock private User user;

    private OAuthExchangeService service;

    @BeforeEach
    void setUp() {
        service = new OAuthExchangeService(loginCodeRepository, signupTokenRepository,
                identityRepository, userRepository, tokenGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void existingIdentityConsumesAndScrubsLoginCodeOnlyAfterCompletionSucceeds() {
        OAuthLoginCode code = availableCode("user@example.com");
        given(loginCodeRepository.findByTokenHashForUpdate(TokenHashing.sha256("login-code")))
                .willReturn(Optional.of(code));
        given(identityRepository.findWithUserByProviderAndProviderSubject(OAuthProvider.GOOGLE, "google-sub"))
                .willReturn(Optional.of(OAuthIdentity.link(user, OAuthProvider.GOOGLE, "google-sub")));
        given(user.isActive()).willReturn(true);

        OAuthExchangeResult<String> result = service.exchange(
                new OAuthExchangeCommand(OAuthProvider.GOOGLE, "login-code"), ignored -> "issued-tokens");

        assertThat(result).isEqualTo(new OAuthExchangeResult.ExistingUser<>("issued-tokens"));
        assertThat(code.getStatus()).isEqualTo(OAuthArtifactStatus.CONSUMED);
        assertThat(code.getConsumedAt()).isEqualTo(NOW);
        assertThat(code.getVerifiedEmail()).isNull();
        then(signupTokenRepository).shouldHaveNoInteractions();
    }

    @Test
    void unlinkedSameEmailRequiresDecisionAndLeavesLoginCodeAvailable() {
        OAuthLoginCode code = availableCode("existing@example.com");
        given(loginCodeRepository.findByTokenHashForUpdate(TokenHashing.sha256("login-code")))
                .willReturn(Optional.of(code));
        given(identityRepository.findWithUserByProviderAndProviderSubject(any(), any()))
                .willReturn(Optional.empty());
        given(userRepository.findByEmailIgnoreCase("existing@example.com")).willReturn(Optional.of(user));

        assertThatThrownBy(() -> service.exchange(
                new OAuthExchangeCommand(OAuthProvider.GOOGLE, "login-code"), ignored -> "never"))
                .isInstanceOf(OAuthFlowException.class)
                .extracting(error -> ((OAuthFlowException) error).getFailure())
                .isEqualTo(OAuthFlowFailure.ACCOUNT_LINK_DECISION_REQUIRED);

        assertThat(code.getStatus()).isEqualTo(OAuthArtifactStatus.AVAILABLE);
        assertThat(code.getVerifiedEmail()).isEqualTo("existing@example.com");
        then(signupTokenRepository).shouldHaveNoInteractions();
    }

    @Test
    void newIdentityIssuesSignupTokenAndConsumesLoginCode() {
        OAuthLoginCode code = availableCode("new@example.com");
        given(loginCodeRepository.findByTokenHashForUpdate(TokenHashing.sha256("login-code")))
                .willReturn(Optional.of(code));
        given(identityRepository.findWithUserByProviderAndProviderSubject(any(), any()))
                .willReturn(Optional.empty());
        given(userRepository.findByEmailIgnoreCase("new@example.com")).willReturn(Optional.empty());
        given(tokenGenerator.generate()).willReturn("signup-token");

        OAuthExchangeResult<String> result = service.exchange(
                new OAuthExchangeCommand(OAuthProvider.GOOGLE, "login-code"), ignored -> "never");

        assertThat(result).isEqualTo(new OAuthExchangeResult.SignupRequired<>(
                "signup-token", NOW.plus(OAuthExchangeService.SIGNUP_TOKEN_TTL)));
        ArgumentCaptor<OAuthSignupToken> saved = ArgumentCaptor.forClass(OAuthSignupToken.class);
        then(signupTokenRepository).should().save(saved.capture());
        assertThat(saved.getValue().getTokenHash()).isEqualTo(TokenHashing.sha256("signup-token"));
        assertThat(saved.getValue().getVerifiedEmail()).isEqualTo("new@example.com");
        assertThat(code.getStatus()).isEqualTo(OAuthArtifactStatus.CONSUMED);
        assertThat(code.getVerifiedEmail()).isNull();
    }

    @Test
    void expiredLoginCodeIsNotConsumedOrExchanged() {
        OAuthLoginCode code = OAuthLoginCode.issue(TokenHashing.sha256("login-code"), OAuthProvider.GOOGLE,
                "google-sub", "user@example.com", NOW);
        given(loginCodeRepository.findByTokenHashForUpdate(TokenHashing.sha256("login-code")))
                .willReturn(Optional.of(code));

        assertThatThrownBy(() -> service.exchange(
                new OAuthExchangeCommand(OAuthProvider.GOOGLE, "login-code"), ignored -> "never"))
                .isInstanceOf(OAuthFlowException.class)
                .extracting(error -> ((OAuthFlowException) error).getFailure())
                .isEqualTo(OAuthFlowFailure.LOGIN_CODE_EXPIRED);

        assertThat(code.getStatus()).isEqualTo(OAuthArtifactStatus.AVAILABLE);
        then(identityRepository).shouldHaveNoInteractions();
    }

    private OAuthLoginCode availableCode(String email) {
        return OAuthLoginCode.issue(TokenHashing.sha256("login-code"), OAuthProvider.GOOGLE,
                "google-sub", email, NOW.plusSeconds(300));
    }
}
