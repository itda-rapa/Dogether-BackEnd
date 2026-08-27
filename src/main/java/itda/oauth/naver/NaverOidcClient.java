package itda.oauth.naver;

import com.fasterxml.jackson.annotation.JsonProperty;
import itda.oauth.domain.OAuthProvider;
import itda.oauth.google.OAuthAuthorizationTransactionStore;
import itda.oauth.google.OAuthCallbackException;
import itda.oauth.google.OAuthCallbackFailure;
import itda.oauth.service.OAuthVerifiedIdentity;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Naver Authorization Code OIDC boundary. Access tokens and raw userinfo never leave this component. */
@Component
public class NaverOidcClient {
    private static final String AUTHORIZATION_ENDPOINT = "https://nid.naver.com/oauth2/authorize";
    private static final String TOKEN_ENDPOINT = "https://nid.naver.com/oauth2/token";
    private static final String JWKS_ENDPOINT = "https://nid.naver.com/oauth2/jwks";
    private static final String USERINFO_ENDPOINT = "https://openapi.naver.com/v1/nid/me";
    private static final String ISSUER = "https://nid.naver.com";
    private static final int MAX_EMAIL_LENGTH = 320;
    private static final Pattern EMAIL = Pattern.compile(
            "^(?!.*\\.\\.)[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@"
                    + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");

    private final NaverOAuthProperties properties;
    private final RestClient tokenClient;
    private final RestClient userInfoClient;
    private final NimbusJwtDecoder jwtDecoder;

    @Autowired
    public NaverOidcClient(NaverOAuthProperties properties) {
        this(properties, restClient(properties), restClient(properties));
    }

    NaverOidcClient(NaverOAuthProperties properties, RestClient tokenClient, RestClient userInfoClient) {
        this(properties, tokenClient, userInfoClient, jwtDecoder(properties));
    }

    NaverOidcClient(
            NaverOAuthProperties properties,
            RestClient tokenClient,
            RestClient userInfoClient,
            NimbusJwtDecoder jwtDecoder
    ) {
        this.properties = properties;
        this.tokenClient = tokenClient;
        this.userInfoClient = userInfoClient;
        this.jwtDecoder = jwtDecoder;
        this.jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), expirationClaimValidator(), issuerValidator(), audienceValidator(properties)));
    }

    public URI authorizationUri(OAuthAuthorizationTransactionStore.CreatedTransaction transaction) {
        requireEnabled();
        return UriComponentsBuilder.fromUriString(AUTHORIZATION_ENDPOINT)
                .queryParam("response_type", "code").queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.backendRedirectUri().toString())
                .queryParam("state", transaction.state())
                .queryParam("scope", "openid")
                .queryParam("code_challenge", transaction.codeChallenge()).queryParam("code_challenge_method", "S256")
                .build().encode(StandardCharsets.UTF_8).toUri();
    }

    public OAuthVerifiedIdentity exchangeAndVerify(String authorizationCode, OAuthAuthorizationTransactionStore.ConsumedTransaction transaction) {
        requireEnabled();
        if (authorizationCode == null || authorizationCode.isBlank()) throw identityFailure();
        try {
            NaverTokenResponse token = tokenClient.post().uri(TOKEN_ENDPOINT).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(tokenRequest(authorizationCode, transaction.codeVerifier(), transaction.state())).retrieve().body(NaverTokenResponse.class);
            if (token == null || blank(token.idToken()) || blank(token.accessToken())) throw identityFailure();
            Jwt idToken = jwtDecoder.decode(token.idToken());
            String subject = requiredClaim(idToken.getSubject());
            String email = userInfoEmail(token.accessToken());
            return new OAuthVerifiedIdentity(OAuthProvider.NAVER, subject, email);
        } catch (OAuthCallbackException exception) { throw exception;
        } catch (JwtException exception) { throw new OAuthCallbackException(jwtFailure(exception));
        } catch (RestClientResponseException exception) {
            throw new OAuthCallbackException(
                    exception.getStatusCode()
                            .is4xxClientError()
                ? OAuthCallbackFailure.OAUTH_IDENTITY_VERIFICATION_FAILED
                            : OAuthCallbackFailure.OAUTH_PROVIDER_UNAVAILABLE
            );
        } catch (ResourceAccessException exception) { throw new OAuthCallbackException(OAuthCallbackFailure.OAUTH_PROVIDER_UNAVAILABLE);
        } catch (RestClientException exception) { throw new OAuthCallbackException(OAuthCallbackFailure.OAUTH_PROVIDER_UNAVAILABLE); }
    }

    static RestClient restClient(NaverOAuthProperties properties) { return RestClient.builder().requestFactory(requestFactory(properties)).build(); }
    static NimbusJwtDecoder jwtDecoder(NaverOAuthProperties properties) {
        return jwtDecoder(properties, JWKS_ENDPOINT);
    }
    static NimbusJwtDecoder jwtDecoder(NaverOAuthProperties properties, String jwksEndpoint) {
        RestOperations jwks = new RestTemplate(requestFactory(properties));
        return NimbusJwtDecoder.withJwkSetUri(jwksEndpoint).jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(jwks).build();
    }
    private static SimpleClientHttpRequestFactory requestFactory(NaverOAuthProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(defaultTimeout(properties.connectTimeout(), Duration.ofSeconds(3)));
        factory.setReadTimeout(defaultTimeout(properties.readTimeout(), Duration.ofSeconds(5)));
        return factory;
    }
    private MultiValueMap<String, String> tokenRequest(String authorizationCode, String verifier, String state) {
        MultiValueMap<String, String> request = new LinkedMultiValueMap<>();
        request.add("grant_type", "authorization_code"); request.add("client_id", properties.clientId());
        request.add("client_secret", properties.clientSecret()); request.add("code", authorizationCode);
        request.add("state", state); request.add("code_verifier", verifier);
        request.add("redirect_uri", properties.backendRedirectUri().toString());
        return request;
    }
    String userInfoEmail(String accessToken) {
        NaverUserInfoResponse response = userInfoClient.get().uri(USERINFO_ENDPOINT)
                .headers(headers -> headers.setBearerAuth(accessToken)).retrieve().body(NaverUserInfoResponse.class);
        return normalizeUserInfoEmail(response);
    }
    private static OAuth2TokenValidator<Jwt> audienceValidator(NaverOAuthProperties properties) {
        return jwt -> {
            List<String> audience = jwt.getAudience();
            // OIDC permits multiple audiences. Dogether has no policy/configuration for trusting an
            // additional Naver audience, so this provider integration currently trusts only its client ID.
            if (audience == null || audience.size() != 1 || !properties.clientId().equals(audience.getFirst())) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            }
            String authorizedParty = jwt.getClaimAsString("azp");
            return authorizedParty == null || properties.clientId().equals(authorizedParty)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
    }
    private static OAuth2TokenValidator<Jwt> issuerValidator() {
        return jwt -> ISSUER.equals(jwt.getClaimAsString("iss")) ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }
    private static OAuth2TokenValidator<Jwt> expirationClaimValidator() {
        return jwt -> jwt.getExpiresAt() != null ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }
    private OAuthCallbackFailure jwtFailure(JwtException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResourceAccessException || cause instanceof RestClientResponseException response && response.getStatusCode().is5xxServerError()) return OAuthCallbackFailure.OAUTH_PROVIDER_UNAVAILABLE;
        }
        return OAuthCallbackFailure.OAUTH_IDENTITY_VERIFICATION_FAILED;
    }
    static String normalizeUserInfoEmail(NaverUserInfoResponse response) {
        if (response == null ||
                !"00".equals(response.resultCode()) ||
                response.response() == null)
            throw identityFailure();
        String email = response.response().email();
        if (!validEmail(email))
            throw identityFailure();
        return email.trim();
    }
    static boolean validEmail(String email) {
        if (email == null) return false;
        String normalized = email.trim();
        return !normalized.isEmpty() && normalized.length() <= MAX_EMAIL_LENGTH && EMAIL.matcher(normalized).matches();
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static OAuthCallbackException identityFailure() { return new OAuthCallbackException(OAuthCallbackFailure.OAUTH_IDENTITY_VERIFICATION_FAILED); }
    private static String requiredClaim(String value) { if (blank(value)) throw identityFailure(); return value; }
    private void requireEnabled() { if (!properties.enabled()) throw new OAuthCallbackException(OAuthCallbackFailure.OAUTH_PROVIDER_UNAVAILABLE); }
    private static Duration defaultTimeout(Duration configured, Duration fallback) { return configured == null ? fallback : configured; }

    record NaverTokenResponse(@JsonProperty("id_token") String idToken, @JsonProperty("access_token") String accessToken) { }
    record NaverUserInfoResponse(@JsonProperty("resultcode") String resultCode, Response response) { }
    record Response(String email) { }
}
