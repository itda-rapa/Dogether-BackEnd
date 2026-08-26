package itda.oauth.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class GoogleOidcClientValidationTest {

    @Test
    void authorizationUriContainsOnlyOidcMinimumScopeAndPkceNonceBindings() {
        GoogleOidcClient client = new GoogleOidcClient(properties(), org.mockito.Mockito.mock(RestClient.class));
        var transaction = new OAuthAuthorizationTransactionStore.CreatedTransaction(
                "state-value", "verifier-value", "nonce-value", Instant.now().plusSeconds(60));

        String uri = client.authorizationUri(transaction).toString();

        assertThat(uri).contains("scope=openid%20email", "state=state-value", "nonce=nonce-value",
                "code_challenge_method=S256", "code_challenge=");
        assertThat(uri).doesNotContain("profile");
    }

    @Test
    void blankAuthorizationCodeIsRejectedBeforeAnyRemoteTokenExchange() {
        GoogleOidcClient client = new GoogleOidcClient(properties(), org.mockito.Mockito.mock(RestClient.class));

        assertThatThrownBy(() -> client.exchangeAndVerify(" ",
                new OAuthAuthorizationTransactionStore.ConsumedTransaction("verifier", "nonce")))
                .isInstanceOf(OAuthCallbackException.class)
                .extracting(error -> ((OAuthCallbackException) error).failure())
                .isEqualTo(OAuthCallbackFailure.OAUTH_IDENTITY_VERIFICATION_FAILED);
    }

    @Test
    void validatorsRejectAbsentExpiryWrongIssuerWrongAudienceAndInvalidPresentAzp() {
        OAuth2TokenValidator<Jwt> expiry = privateValidator("expirationClaimValidator");
        OAuth2TokenValidator<Jwt> issuer = privateValidator("issuerValidator");
        OAuth2TokenValidator<Jwt> audience = audienceValidator();

        assertThat(expiry.validate(jwt(null, "https://accounts.google.com", List.of("client-id"), null))
                .hasErrors()).isTrue();
        assertThat(issuer.validate(jwt(Instant.now().plusSeconds(300), "https://evil.example", List.of("client-id"), null))
                .hasErrors()).isTrue();
        assertThat(audience.validate(jwt(Instant.now().plusSeconds(300), "https://accounts.google.com", List.of("wrong"), null))
                .hasErrors()).isTrue();
        assertThat(audience.validate(jwt(Instant.now().plusSeconds(300), "https://accounts.google.com",
                List.of("client-id", "other"), null)).hasErrors()).isFalse();
        assertThat(audience.validate(jwt(Instant.now().plusSeconds(300), "https://accounts.google.com",
                List.of("client-id"), null)).hasErrors()).isFalse();
        assertThat(audience.validate(jwt(Instant.now().plusSeconds(300), "accounts.google.com",
                List.of("client-id", "other"), "client-id")).hasErrors()).isFalse();
        assertThat(audience.validate(jwt(Instant.now().plusSeconds(300), "accounts.google.com",
                List.of("client-id"), "")).hasErrors()).isTrue();
        assertThat(audience.validate(jwt(Instant.now().plusSeconds(300), "accounts.google.com",
                List.of("client-id"), "other-client")).hasErrors()).isTrue();
    }

    @Test
    void maintainedDefaultValidatorRejectsExpiredToken() {
        Jwt expired = jwt(Instant.now().minusSeconds(120), "https://accounts.google.com", List.of("client-id"), null);
        assertThat(JwtValidators.createDefault().validate(expired).hasErrors()).isTrue();
    }

    @Test
    void nonceAndRequiredClaimsRejectMissingOrUnverifiedIdentityDataWhileNoHdClaimIsRequired() {
        GoogleOidcClient client = new GoogleOidcClient(properties(), org.mockito.Mockito.mock(RestClient.class));
        Jwt thirdPartyVerifiedEmail = identityJwt("nonce", "subject", "person@third-party.example", true);

        assertThat((String) ReflectionTestUtils.invokeMethod(client, "requiredClaim",
                thirdPartyVerifiedEmail.getClaimAsString("email")))
                .isEqualTo("person@third-party.example");
        assertThat(thirdPartyVerifiedEmail.hasClaim("hd")).isFalse();
        assertThat((Object) ReflectionTestUtils.invokeMethod(client, "validateNonce", thirdPartyVerifiedEmail, "nonce"))
                .isNull();
        for (Jwt invalid : List.of(
                identityJwt("wrong", "subject", "person@example.com", true),
                identityJwt("nonce", null, "person@example.com", true),
                identityJwt("nonce", "subject", null, true),
                identityJwt("nonce", "subject", "person@example.com", false)
        )) {
            if (!"nonce".equals(invalid.getClaimAsString("nonce"))) {
                assertIdentityFailure(() -> ReflectionTestUtils.invokeMethod(client, "validateNonce", invalid, "nonce"));
            } else if (invalid.getSubject() == null || invalid.getClaimAsString("email") == null) {
                assertIdentityFailure(() -> ReflectionTestUtils.invokeMethod(client, "requiredClaim",
                        invalid.getSubject() == null ? invalid.getSubject() : invalid.getClaimAsString("email")));
            } else {
                assertThat(Boolean.TRUE.equals(invalid.getClaim("email_verified"))).isFalse();
            }
        }
    }

    @Test
    void jwksTransportFailureMapsUnavailableWhileOtherJwtFailuresMapIdentityVerificationFailure() {
        GoogleOidcClient client = new GoogleOidcClient(properties(), org.mockito.Mockito.mock(RestClient.class));
        assertThat((OAuthCallbackFailure) ReflectionTestUtils.invokeMethod(client, "jwtFailure",
                new org.springframework.security.oauth2.jwt.JwtException("JWKS timeout", new ResourceAccessException("timeout"))))
                .isEqualTo(OAuthCallbackFailure.OAUTH_PROVIDER_UNAVAILABLE);
        assertThat((OAuthCallbackFailure) ReflectionTestUtils.invokeMethod(client, "jwtFailure",
                new org.springframework.security.oauth2.jwt.JwtException("JWKS 503",
                        HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", null,
                                new byte[0], null))))
                .isEqualTo(OAuthCallbackFailure.OAUTH_PROVIDER_UNAVAILABLE);
        assertThat((OAuthCallbackFailure) ReflectionTestUtils.invokeMethod(client, "jwtFailure",
                new org.springframework.security.oauth2.jwt.JwtException("signature invalid")))
                .isEqualTo(OAuthCallbackFailure.OAUTH_IDENTITY_VERIFICATION_FAILED);
    }

    @SuppressWarnings("unchecked")
    private OAuth2TokenValidator<Jwt> privateValidator(String method) {
        return (OAuth2TokenValidator<Jwt>) ReflectionTestUtils.invokeMethod(GoogleOidcClient.class, method);
    }

    @SuppressWarnings("unchecked")
    private OAuth2TokenValidator<Jwt> audienceValidator() {
        return (OAuth2TokenValidator<Jwt>) ReflectionTestUtils.invokeMethod(
                GoogleOidcClient.class, "audienceValidator", properties());
    }

    private Jwt jwt(Instant expiresAt, String issuer, List<String> audience, String azp) {
        Instant issuedAt = expiresAt != null && expiresAt.isBefore(Instant.now())
                ? expiresAt.minusSeconds(60) : Instant.now().minusSeconds(60);
        var builder = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .claim("iss", issuer)
                .claim("aud", audience)
                .claim("iat", issuedAt);
        if (expiresAt != null) builder.claim("exp", expiresAt);
        if (azp != null) builder.claim("azp", azp);
        return builder.build();
    }

    private Jwt identityJwt(String nonce, String subject, String email, boolean emailVerified) {
        var builder = Jwt.withTokenValue("identity-token")
                .header("alg", "none")
                .claim("nonce", nonce)
                .claim("email_verified", emailVerified)
                .claim("iat", Instant.now().minusSeconds(60))
                .claim("exp", Instant.now().plusSeconds(300));
        if (subject != null) builder.subject(subject);
        if (email != null) builder.claim("email", email);
        return builder.build();
    }

    private void assertIdentityFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(OAuthCallbackException.class);
    }

    private GoogleOAuthProperties properties() {
        return new GoogleOAuthProperties(true, "client-id", "client-secret",
                "https://api.example.com/login/oauth2/code/google",
                "https://front.example.com/oauth/success", "https://front.example.com/oauth/error",
                List.of("https://front.example.com/oauth/success", "https://front.example.com/oauth/error"),
                Duration.ofMinutes(1), Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(1));
    }
}
