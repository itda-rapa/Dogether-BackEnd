package itda.oauth.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import itda.oauth.google.OAuthAuthorizationTransactionStore;
import itda.oauth.google.OAuthCallbackException;
import itda.oauth.google.OAuthCallbackFailure;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;

class NaverOidcClientValidationTest {

    @Test
    void authorizationUriUsesNaverOidcPkceAndDoesNotAddGoogleNonce() {
        NaverOidcClient client = new NaverOidcClient(properties(), org.mockito.Mockito.mock(RestClient.class), org.mockito.Mockito.mock(RestClient.class));
        var transaction = new OAuthAuthorizationTransactionStore.CreatedTransaction("state-value", "verifier-value", null, Instant.now().plusSeconds(60));

        MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(client.authorizationUri(transaction))
                .build()
                .getQueryParams();

        assertThat(query).containsEntry("response_type", List.of("code"))
                .containsEntry("client_id", List.of("client-id"))
                .containsEntry("redirect_uri", List.of("https://api.example.com/login/oauth2/code/naver"))
                .containsEntry("state", List.of("state-value"))
                .containsEntry("code_challenge_method", List.of("S256"));
        assertThat(query.get("code_challenge")).hasSize(1);
        assertThat(query).containsKey("scope");
        assertThat(query.get("scope")).containsExactly("openid");
        assertThat(query).doesNotContainKeys("nonce", "email");
    }

    @Test
    void tokenFormUsesClientSecretPostAndAuthorizationRedirectUri() {
        NaverOidcClient client = new NaverOidcClient(properties(), org.mockito.Mockito.mock(RestClient.class), org.mockito.Mockito.mock(RestClient.class));

        @SuppressWarnings("unchecked")
        MultiValueMap<String, String> form = (MultiValueMap<String, String>) ReflectionTestUtils.invokeMethod(client,
                "tokenRequest", "code-value", "verifier-value", "state-value");

        assertThat(form).containsEntry("grant_type", List.of("authorization_code"))
                .containsEntry("client_id", List.of("client-id"))
                .containsEntry("client_secret", List.of("client-secret"))
                .containsEntry("code", List.of("code-value"))
                .containsEntry("state", List.of("state-value"))
                .containsEntry("code_verifier", List.of("verifier-value"))
                .containsEntry("redirect_uri", List.of("https://api.example.com/login/oauth2/code/naver"));
        assertThat(form.get("redirect_uri")).hasSize(1);
    }

    @Test
    void idTokenValidatorsTrustOnlyTheConfiguredNaverClientAudienceAndMatchingOptionalAzp() {
        OAuth2TokenValidator<Jwt> expiry = validator("expirationClaimValidator");
        OAuth2TokenValidator<Jwt> issuer = validator("issuerValidator");
        OAuth2TokenValidator<Jwt> audience = audienceValidator();

        assertThat(expiry.validate(jwt(null, "https://nid.naver.com", List.of("client-id"), "sub")).hasErrors()).isTrue();
        assertThat(issuer.validate(jwt(Instant.now().plusSeconds(60), "https://evil.example", List.of("client-id"), "sub")).hasErrors()).isTrue();
        assertThat(audience.validate(jwt(Instant.now().plusSeconds(60), "https://nid.naver.com", List.of("client-id"), "sub", null)).hasErrors()).isFalse();
        assertThat(audience.validate(jwt(Instant.now().plusSeconds(60), "https://nid.naver.com", List.of("wrong"), "sub", null)).hasErrors()).isTrue();
        assertThat(audience.validate(jwt(Instant.now().plusSeconds(60), "https://nid.naver.com", List.of("client-id", "other"), "sub", null)).hasErrors()).isTrue();
        assertThat(audience.validate(jwt(Instant.now().plusSeconds(60), "https://nid.naver.com", List.of(), "sub", null)).hasErrors()).isTrue();
        assertThat(audience.validate(jwt(Instant.now().plusSeconds(60), "https://nid.naver.com", List.of("client-id"), "sub", "client-id")).hasErrors()).isFalse();
        assertThat(audience.validate(jwt(Instant.now().plusSeconds(60), "https://nid.naver.com", List.of("client-id"), "sub", "other")).hasErrors()).isTrue();

        NaverOidcClient client = new NaverOidcClient(properties(), org.mockito.Mockito.mock(RestClient.class), org.mockito.Mockito.mock(RestClient.class));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(client, "requiredClaim", " "))
                .isInstanceOf(OAuthCallbackException.class)
                .extracting(error -> ((OAuthCallbackException) error).failure())
                .isEqualTo(OAuthCallbackFailure.OAUTH_IDENTITY_VERIFICATION_FAILED);
    }

    @Test
    void userInfoEmailNormalizationRejectsMissingBlankAndMalformedValues() {
        assertThat(NaverOidcClient.normalizeUserInfoEmail(new NaverOidcClient.NaverUserInfoResponse("00",
                new NaverOidcClient.Response("person@example.com")))).isEqualTo("person@example.com");
        for (NaverOidcClient.NaverUserInfoResponse invalid : List.of(
                new NaverOidcClient.NaverUserInfoResponse("01", new NaverOidcClient.Response("person@example.com")),
                new NaverOidcClient.NaverUserInfoResponse("00", null),
                new NaverOidcClient.NaverUserInfoResponse("00", new NaverOidcClient.Response(null)),
                new NaverOidcClient.NaverUserInfoResponse("00", new NaverOidcClient.Response(" ")),
                new NaverOidcClient.NaverUserInfoResponse("00", new NaverOidcClient.Response("person..name@example.com")),
                new NaverOidcClient.NaverUserInfoResponse("00", new NaverOidcClient.Response("invalid-email"))
        )) {
            assertThatThrownBy(() -> NaverOidcClient.normalizeUserInfoEmail(invalid))
                    .isInstanceOf(OAuthCallbackException.class)
                    .extracting(error -> ((OAuthCallbackException) error).failure())
                    .isEqualTo(OAuthCallbackFailure.OAUTH_IDENTITY_VERIFICATION_FAILED);
        }
        assertThat(NaverOidcClient.validEmail("a".repeat(309) + "@example.com")).isFalse();
    }

    @Test
    void userInfoHttpBoundaryRejectsProviderFailuresAndMalformedResponses() {
        for (NaverOidcClient.NaverUserInfoResponse invalid : List.of(
                new NaverOidcClient.NaverUserInfoResponse("03", new NaverOidcClient.Response("person@example.com")),
                new NaverOidcClient.NaverUserInfoResponse("00", null),
                new NaverOidcClient.NaverUserInfoResponse("00", new NaverOidcClient.Response(null)),
                new NaverOidcClient.NaverUserInfoResponse("00", new NaverOidcClient.Response("  ")),
                new NaverOidcClient.NaverUserInfoResponse("00", new NaverOidcClient.Response("person..name@example.com"))
        )) {
            assertThatThrownBy(() -> userInfoClientReturning(invalid).userInfoEmail("access-token"))
                    .isInstanceOf(OAuthCallbackException.class)
                    .extracting(error -> ((OAuthCallbackException) error).failure())
                    .isEqualTo(OAuthCallbackFailure.OAUTH_IDENTITY_VERIFICATION_FAILED);
        }
        assertThatThrownBy(() -> userInfoClientFailing(new ResourceAccessException("timeout")).userInfoEmail("access-token"))
                .isInstanceOf(ResourceAccessException.class);
        assertThatThrownBy(() -> userInfoClientFailing(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE,
                "unavailable", null, new byte[0], null)).userInfoEmail("access-token"))
                .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void localJwksDecoderAcceptsOnlyValidRs256Signatures() throws Exception {
        RSAKey trusted = new RSAKeyGenerator(2048).keyID("trusted").generate();
        byte[] jwks = new JWKSet(trusted.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/jwks", exchange -> {
            exchange.sendResponseHeaders(200, jwks.length);
            exchange.getResponseBody().write(jwks);
            exchange.close();
        });
        server.start();
        try {
            NimbusJwtDecoder decoder = NaverOidcClient.jwtDecoder(properties(),
                    "http://localhost:" + server.getAddress().getPort() + "/jwks");
            decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                    org.springframework.security.oauth2.jwt.JwtValidators.createDefault(), validator("expirationClaimValidator"),
                    validator("issuerValidator"), audienceValidator()));

            assertThat(decoder.decode(token(trusted, JWSAlgorithm.RS256, "client-id").serialize()).getSubject()).isEqualTo("subject");
            RSAKey untrusted = new RSAKeyGenerator(2048).keyID("trusted").generate();
            assertThatThrownBy(() -> decoder.decode(token(untrusted, JWSAlgorithm.RS256, "client-id").serialize()))
                    .isInstanceOf(JwtException.class);
            assertThatThrownBy(() -> decoder.decode(token(trusted, JWSAlgorithm.RS512, "client-id").serialize()))
                    .isInstanceOf(JwtException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void jwksConnectAndServerFailuresMapToProviderUnavailableThroughTheCallbackPath() throws Exception {
        RSAKey signingKey = new RSAKeyGenerator(2048).keyID("missing").generate();
        NaverOidcClient.NaverTokenResponse tokenResponse = new NaverOidcClient.NaverTokenResponse(
                token(signingKey, JWSAlgorithm.RS256, "client-id").serialize(), "access-token");

        HttpServer unavailable = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        unavailable.createContext("/jwks", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        unavailable.start();
        try {
            assertFailure(clientWithTokenResponseAndDecoder(tokenResponse, NaverOidcClient.jwtDecoder(properties(),
                    "http://localhost:" + unavailable.getAddress().getPort() + "/jwks")),
                    OAuthCallbackFailure.OAUTH_PROVIDER_UNAVAILABLE);
        } finally {
            unavailable.stop(0);
        }

        HttpServer closed = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        int closedPort = closed.getAddress().getPort();
        closed.stop(0);
        assertFailure(clientWithTokenResponseAndDecoder(tokenResponse, NaverOidcClient.jwtDecoder(properties(),
                "http://localhost:" + closedPort + "/jwks")), OAuthCallbackFailure.OAUTH_PROVIDER_UNAVAILABLE);
    }

    @Test
    void malformedNaverTokenResponseIsAnIdentityVerificationFailure() {
        NaverOidcClient client = clientWhoseTokenResponse(null);

        assertFailure(client, OAuthCallbackFailure.OAUTH_IDENTITY_VERIFICATION_FAILED);
    }

    @Test
    void naverTokenTimeoutAndServerFailureAreProviderUnavailable() {
        assertFailure(clientWhoseTokenRequestFails(new ResourceAccessException("timeout")),
                OAuthCallbackFailure.OAUTH_PROVIDER_UNAVAILABLE);
        assertFailure(clientWhoseTokenRequestFails(HttpServerErrorException.create(
                        HttpStatus.SERVICE_UNAVAILABLE, "unavailable", null, new byte[0], null)),
                OAuthCallbackFailure.OAUTH_PROVIDER_UNAVAILABLE);
    }

    private void assertFailure(NaverOidcClient client, OAuthCallbackFailure expected) {
        assertThatThrownBy(() -> client.exchangeAndVerify("authorization-code",
                new OAuthAuthorizationTransactionStore.ConsumedTransaction("state", "verifier", null)))
                .isInstanceOf(OAuthCallbackException.class)
                .extracting(error -> ((OAuthCallbackException) error).failure())
                .isEqualTo(expected);
    }

    private NaverOidcClient clientWhoseTokenResponse(Object tokenResponse) {
        RestClient.ResponseSpec response = tokenResponse == null
                ? tokenResponseSpecReturning(null) : tokenResponseSpecReturning(tokenResponse);
        return clientWithTokenResponse(response);
    }

    private NaverOidcClient clientWhoseTokenRequestFails(RuntimeException failure) {
        RestClient.ResponseSpec response = org.mockito.Mockito.mock(RestClient.ResponseSpec.class);
        given(response.body(any(Class.class))).willThrow(failure);
        return clientWithTokenResponse(response);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private RestClient.ResponseSpec tokenResponseSpecReturning(Object value) {
        RestClient.ResponseSpec response = org.mockito.Mockito.mock(RestClient.ResponseSpec.class);
        given(response.body(any(Class.class))).willReturn(value);
        return response;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private NaverOidcClient clientWithTokenResponse(RestClient.ResponseSpec response) {
        return clientWithTokenResponse(response, NaverOidcClient.jwtDecoder(properties()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private NaverOidcClient clientWithTokenResponseAndDecoder(
            NaverOidcClient.NaverTokenResponse tokenResponse,
            NimbusJwtDecoder decoder
    ) {
        return clientWithTokenResponse(tokenResponseSpecReturning(tokenResponse), decoder);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private NaverOidcClient clientWithTokenResponse(RestClient.ResponseSpec response, NimbusJwtDecoder decoder) {
        RestClient tokenClient = org.mockito.Mockito.mock(RestClient.class);
        RestClient.RequestBodyUriSpec post = org.mockito.Mockito.mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec request = org.mockito.Mockito.mock(RestClient.RequestBodySpec.class);
        given(tokenClient.post()).willReturn(post);
        given(post.uri("https://nid.naver.com/oauth2/token")).willReturn(request);
        given(request.contentType(any())).willReturn(request);
        given(request.body(any(org.springframework.util.MultiValueMap.class))).willReturn(request);
        given(request.retrieve()).willReturn(response);
        return new NaverOidcClient(properties(), tokenClient, org.mockito.Mockito.mock(RestClient.class), decoder);
    }

    @SuppressWarnings("unchecked")
    private OAuth2TokenValidator<Jwt> validator(String method) {
        return (OAuth2TokenValidator<Jwt>) ReflectionTestUtils.invokeMethod(NaverOidcClient.class, method);
    }
    @SuppressWarnings("unchecked")
    private OAuth2TokenValidator<Jwt> audienceValidator() {
        return (OAuth2TokenValidator<Jwt>) ReflectionTestUtils.invokeMethod(NaverOidcClient.class, "audienceValidator", properties());
    }
    private Jwt jwt(Instant expiry, String issuer, List<String> audience, String subject) {
        return jwt(expiry, issuer, audience, subject, null);
    }
    private Jwt jwt(Instant expiry, String issuer, List<String> audience, String subject, String azp) {
        var builder = Jwt.withTokenValue("token").header("alg", "RS256").claim("iss", issuer).claim("aud", audience)
                .claim("iat", Instant.now().minusSeconds(60));
        if (expiry != null) builder.claim("exp", expiry);
        if (subject != null) builder.subject(subject);
        if (azp != null) builder.claim("azp", azp);
        return builder.build();
    }
    private NaverOAuthProperties properties() {
        return new NaverOAuthProperties(true, "client-id", "client-secret", "https://api.example.com/login/oauth2/code/naver",
                "https://front.example.com/oauth/success", "https://front.example.com/oauth/error",
                List.of("https://front.example.com/oauth/success", "https://front.example.com/oauth/error"),
                Duration.ofMinutes(1), Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private NaverOidcClient userInfoClientReturning(NaverOidcClient.NaverUserInfoResponse userInfo) {
        RestClient.ResponseSpec response = org.mockito.Mockito.mock(RestClient.ResponseSpec.class);
        given(response.body(NaverOidcClient.NaverUserInfoResponse.class)).willReturn(userInfo);
        return clientWithUserInfoResponse(response);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private NaverOidcClient userInfoClientFailing(RuntimeException failure) {
        RestClient.ResponseSpec response = org.mockito.Mockito.mock(RestClient.ResponseSpec.class);
        given(response.body(NaverOidcClient.NaverUserInfoResponse.class)).willThrow(failure);
        return clientWithUserInfoResponse(response);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private NaverOidcClient clientWithUserInfoResponse(RestClient.ResponseSpec response) {
        RestClient userInfoClient = org.mockito.Mockito.mock(RestClient.class);
        RestClient.RequestHeadersUriSpec get = org.mockito.Mockito.mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec request = org.mockito.Mockito.mock(RestClient.RequestHeadersSpec.class);
        given(userInfoClient.get()).willReturn(get);
        given(get.uri("https://openapi.naver.com/v1/nid/me")).willReturn(request);
        given(request.headers(any())).willReturn(request);
        given(request.retrieve()).willReturn(response);
        return new NaverOidcClient(properties(), org.mockito.Mockito.mock(RestClient.class), userInfoClient);
    }

    private SignedJWT token(RSAKey key, JWSAlgorithm algorithm, String audience) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(key.getKeyID()).build(), new JWTClaimsSet.Builder()
                .issuer("https://nid.naver.com").audience(audience).subject("subject")
                .issueTime(Date.from(Instant.now().minusSeconds(10))).expirationTime(Date.from(Instant.now().plusSeconds(300))).build());
        jwt.sign(new RSASSASigner(key.toPrivateKey()));
        return jwt;
    }
}
