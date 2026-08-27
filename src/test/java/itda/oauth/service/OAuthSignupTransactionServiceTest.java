package itda.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import itda.common.security.TokenHashing;
import itda.neighborhood.repository.NeighborhoodRepository;
import itda.oauth.domain.OAuthArtifactStatus;
import itda.oauth.domain.OAuthIdentity;
import itda.oauth.domain.OAuthProvider;
import itda.oauth.domain.OAuthSignupToken;
import itda.oauth.repository.OAuthIdentityRepository;
import itda.oauth.repository.OAuthSignupTokenRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class OAuthSignupTransactionServiceTest {

    @Test
    void acceptsTrimmedNicknameAtTwoAndTwentyCharactersButRejectsTwentyOne() {
        OAuthSignupTransactionService service = new OAuthSignupTransactionService(
                org.mockito.Mockito.mock(OAuthSignupTokenRepository.class),
                org.mockito.Mockito.mock(OAuthIdentityRepository.class),
                org.mockito.Mockito.mock(UserRepository.class),
                org.mockito.Mockito.mock(NeighborhoodRepository.class), Clock.systemUTC());

        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeNickname", " 가나 "))
                .isEqualTo("가나");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeNickname", "가".repeat(20)))
                .isEqualTo("가".repeat(20));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service, "normalizeNickname", "가".repeat(21)))
                .isInstanceOf(OAuthFlowException.class);
    }

    @ParameterizedTest
    @EnumSource(OAuthProvider.class)
    void commonGoogleAndNaverCompletionCreatesPasswordlessUserLinksIdentityAndConsumesToken(
            OAuthProvider provider
    ) {
        CompletionFixture fixture = completionFixture(provider);

        String completed = fixture.service().completeAttempt(
                new OAuthSignupCommand("signup-token", " 사용자 ", " 1168010100 ",
                        new BigDecimal("3.25")),
                "사용자#A7K2", user -> "completed");

        assertThat(completed).isEqualTo("completed");
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        then(fixture.userRepository()).should().saveAndFlush(savedUser.capture());
        assertThat(savedUser.getValue().getPasswordHash()).isNull();
        assertThat(savedUser.getValue().getWeightKg()).isEqualByComparingTo("3.25");
        ArgumentCaptor<OAuthIdentity> savedIdentity = ArgumentCaptor.forClass(OAuthIdentity.class);
        then(fixture.identityRepository()).should().saveAndFlush(savedIdentity.capture());
        assertThat(savedIdentity.getValue().getUser()).isSameAs(savedUser.getValue());
        assertThat(savedIdentity.getValue().getProvider()).isEqualTo(provider);
        assertThat(fixture.signupToken().getStatus()).isEqualTo(OAuthArtifactStatus.CONSUMED);
        assertThat(fixture.signupToken().getVerifiedEmail()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.99", "500.01", "72.123"})
    void directCompletionRejectsInvalidWeightAsValidationFailureWithoutConsumingToken(
            String invalidWeightKg
    ) {
        CompletionFixture fixture = completionFixture(OAuthProvider.GOOGLE);

        assertThatThrownBy(() -> fixture.service().completeAttempt(
                new OAuthSignupCommand("signup-token", "사용자", "1168010100", new BigDecimal(invalidWeightKg)),
                "사용자#A7K2", user -> "done"))
                .isInstanceOfSatisfying(OAuthFlowException.class, exception ->
                        assertThat(exception.getFailure()).isEqualTo(OAuthFlowFailure.VALIDATION_FAILED))
                .isNotInstanceOf(IllegalArgumentException.class);

        then(fixture.userRepository()).shouldHaveNoInteractions();
        then(fixture.identityRepository()).shouldHaveNoInteractions();
        assertThat(fixture.signupToken().getStatus()).isEqualTo(OAuthArtifactStatus.AVAILABLE);
        assertThat(fixture.signupToken().getVerifiedEmail()).isEqualTo("oauth@example.com");
    }

    @Test
    void failedIdentityPersistenceLeavesSignupTokenRetryableUntilSuccessfulCompletion() {
        CompletionFixture fixture = completionFixture(OAuthProvider.GOOGLE);
        given(fixture.identityRepository().saveAndFlush(any(OAuthIdentity.class)))
                .willThrow(new IllegalStateException("forced identity failure"))
                .willAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> fixture.service().completeAttempt(
                new OAuthSignupCommand("signup-token", "사용자", "1168010100", null),
                "사용자#A7K2", user -> "done"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fixture.signupToken().getStatus()).isEqualTo(OAuthArtifactStatus.AVAILABLE);
        assertThat(fixture.signupToken().getVerifiedEmail()).isEqualTo("oauth@example.com");

        assertThat(fixture.service().<String>completeAttempt(
                new OAuthSignupCommand("signup-token", "사용자", "1168010100", null),
                "사용자#A7K3", user -> "retried"))
                .isEqualTo("retried");
        then(fixture.identityRepository()).should(times(2)).saveAndFlush(any(OAuthIdentity.class));
        assertThat(fixture.signupToken().getStatus()).isEqualTo(OAuthArtifactStatus.CONSUMED);
        assertThat(fixture.signupToken().getVerifiedEmail()).isNull();
    }

    private CompletionFixture completionFixture(OAuthProvider provider) {
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        String providerSubject = provider.name().toLowerCase() + "-subject";
        OAuthSignupToken signupToken = OAuthSignupToken.issue(
                TokenHashing.sha256("signup-token"), provider, providerSubject,
                "oauth@example.com", now.plusSeconds(60));
        OAuthSignupTokenRepository signupTokenRepository = org.mockito.Mockito.mock(OAuthSignupTokenRepository.class);
        OAuthIdentityRepository identityRepository = org.mockito.Mockito.mock(OAuthIdentityRepository.class);
        UserRepository userRepository = org.mockito.Mockito.mock(UserRepository.class);
        NeighborhoodRepository neighborhoodRepository = org.mockito.Mockito.mock(NeighborhoodRepository.class);
        given(signupTokenRepository.findByTokenHashForUpdate(TokenHashing.sha256("signup-token")))
                .willReturn(Optional.of(signupToken));
        given(neighborhoodRepository.existsByCodeAndActiveTrue("1168010100")).willReturn(true);
        given(userRepository.findByEmailIgnoreCase("oauth@example.com")).willReturn(Optional.empty());
        given(identityRepository.existsByProviderAndProviderSubject(provider, providerSubject)).willReturn(false);
        given(userRepository.saveAndFlush(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));
        OAuthSignupTransactionService service = new OAuthSignupTransactionService(
                signupTokenRepository, identityRepository, userRepository, neighborhoodRepository,
                Clock.fixed(now, ZoneOffset.UTC));
        return new CompletionFixture(
                service, signupToken, identityRepository, userRepository);
    }

    private record CompletionFixture(
            OAuthSignupTransactionService service,
            OAuthSignupToken signupToken,
            OAuthIdentityRepository identityRepository,
            UserRepository userRepository
    ) {
    }
}
