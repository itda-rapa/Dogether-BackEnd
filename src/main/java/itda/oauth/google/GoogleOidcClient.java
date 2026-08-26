package itda.oauth.google;

import com.fasterxml.jackson.annotation.JsonProperty;
import itda.oauth.domain.OAuthProvider;
import itda.oauth.service.OAuthVerifiedIdentity;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
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
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Google-only Authorization Code + OIDC boundary. Provider tokens are never persisted. */
@Component
public class GoogleOidcClient {

    private static final String AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String JWKS_ENDPOINT = "https://www.googleapis.com/oauth2/v3/certs";
    private static final String ISSUER = "https://accounts.google.com";

    private final GoogleOAuthProperties properties;
    private final RestClient tokenClient;
    private final NimbusJwtDecoder jwtDecoder;

    @Autowired
    public GoogleOidcClient(GoogleOAuthProperties properties) {
        this(properties, restClient(properties));
    }

    GoogleOidcClient(GoogleOAuthProperties properties, RestClient tokenClient) {
        this.properties = properties;
        this.tokenClient = tokenClient;
        this.jwtDecoder = jwtDecoder(properties);
        this.jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), expirationClaimValidator(), issuerValidator(), audienceValidator(properties)
        ));
    }

    public URI authorizationUri(OAuthAuthorizationTransactionStore.CreatedTransaction transaction) {
        requireEnabled();
        return UriComponentsBuilder.fromUriString(AUTHORIZATION_ENDPOINT)
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.backendRedirectUri().toString())
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email")
                .queryParam("state", transaction.state())
                .queryParam("nonce", transaction.nonce())
                .queryParam("code_challenge", transaction.codeChallenge())
                .queryParam("code_challenge_method", "S256")
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUri();
    }

    public OAuthVerifiedIdentity exchangeAndVerify(
            String authorizationCode,
            OAuthAuthorizationTransactionStore.ConsumedTransaction transaction
    ) {
        requireEnabled();
        if (authorizationCode == null || authorizationCode.isBlank()) {
            throw new OAuthCallbackException(OAuthCallbackFailure.OAUTH_IDENTITY_VERIFICATION_FAILED);
        }
        try {
            GoogleTokenResponse response = tokenClient.post()
                    .uri(TOKEN_ENDPOINT)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(tokenRequest(authorizationCode, transaction.codeVerifier()))
                    .retrieve()
                    .body(GoogleTokenResponse.class);
            if (response == null || response.idToken() == null || response.idToken().isBlank()) {
                throw new OAuthCallbackException(OAuthCallbackFailure.OAUTH_IDENTITY_VERIFICATION_FAILED);
            }
            Jwt idToken = jwtDecoder.decode(response.idToken());
            validateNonce(idToken, transaction.nonce());
            String subject = requiredClaim(idToken.getSubject());
            String email = requiredClaim(idToken.getClaimAsString("email"));
            if (!Boolean.TRUE.equals(idToken.getClaim("email_verified"))) {
                throw new OAuthCallbackException(OAuthCallbackFailure.OAUTH_IDENTITY_VERIFICATION_FAILED);
            }
            return new OAuthVerifiedIdentity(OAuthProvider.GOOGLE, subject, email);
        } catch (OAuthCallbackException exception) {
            throw exception;
        } catch (JwtException exception) {
            throw new OAuthCallbackException(jwtFailure(exception));
        } catch (RestClientResponseException exception) {
            throw new OAuthCallbackException(exception.getStatusCode().is4xxClientError()
                    ? OAuthCallbackFailure.OAUTH_IDENTITY_VERIFICATION_FAILED
                    : OAuthCallbackFailure.OAUTH_PROVIDER_UNAVAILABLE);
        } catch (ResourceAccessException exception) {
            throw new OAuthCallbackException(OAuthCallbackFailure.OAUTH_PROVIDER_UNAVAILABLE);
        } catch (RestClientException exception) {
            throw new OAuthCallbackException(OAuthCallbackFailure.OAUTH_PROVIDER_UNAVAILABLE);
        }
    }

    static RestClient restClient(GoogleOAuthProperties properties) {
        return RestClient.builder().requestFactory(requestFactory(properties)).build();
    }

    /**
     * NimbusJwtDecoder officially supports injecting RestOperations. This gives JWKS retrieval the
     * same bounded connect/read behavior as the Google token exchange without custom JWK handling.
     */
    static NimbusJwtDecoder jwtDecoder(GoogleOAuthProperties properties) {
        RestOperations jwkRestOperations = new RestTemplate(requestFactory(properties));
        return NimbusJwtDecoder.withJwkSetUri(JWKS_ENDPOINT)
                .restOperations(jwkRestOperations)
                .build();
    }

    private static SimpleClientHttpRequestFactory requestFactory(GoogleOAuthProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(defaultTimeout(properties.connectTimeout(), Duration.ofSeconds(3)));
        factory.setReadTimeout(defaultTimeout(properties.readTimeout(), Duration.ofSeconds(5)));
        return factory;
    }

    private MultiValueMap<String, String> tokenRequest(String authorizationCode, String verifier) {
        MultiValueMap<String, String> request = new LinkedMultiValueMap<>();
        request.add("code", authorizationCode);
        request.add("client_id", properties.clientId());
        request.add("client_secret", properties.clientSecret());
        request.add("redirect_uri", properties.backendRedirectUri().toString());
        request.add("grant_type", "authorization_code");
        request.add("code_verifier", verifier);
        return request;
    }

    private static OAuth2TokenValidator<Jwt> audienceValidator(GoogleOAuthProperties properties) {
        return jwt -> {
            List<String> audience = jwt.getAudience();
            // Dogether configures one Google client and no trusted additional audiences.
            boolean expectedAudience = audience != null
                    && audience.size() == 1
                    && properties.clientId().equals(audience.getFirst());
            String authorizedParty = jwt.getClaimAsString("azp");
            // Google does not require azp for this client. If it is supplied, bind it to this client.
            boolean authorizedPartyValid = authorizedParty == null
                    || properties.clientId().equals(authorizedParty);
            return expectedAudience && authorizedPartyValid
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
    }

    private static OAuth2TokenValidator<Jwt> issuerValidator() {
        return jwt -> {
            String issuer = jwt.getClaimAsString("iss");
            return ISSUER.equals(issuer) || "accounts.google.com".equals(issuer)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
    }

    /** JwtValidators validates expiry when present; OIDC requires that exp is present as well. */
    private static OAuth2TokenValidator<Jwt> expirationClaimValidator() {
        return jwt -> jwt.getExpiresAt() != null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }

    private OAuthCallbackFailure jwtFailure(JwtException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResourceAccessException) {
                return OAuthCallbackFailure.OAUTH_PROVIDER_UNAVAILABLE;
            }
            if (cause instanceof RestClientResponseException response
                    && response.getStatusCode().is5xxServerError()) {
                return OAuthCallbackFailure.OAUTH_PROVIDER_UNAVAILABLE;
            }
        }
        return OAuthCallbackFailure.OAUTH_IDENTITY_VERIFICATION_FAILED;
    }

    private void validateNonce(Jwt token, String expectedNonce) {
        String actualNonce = token.getClaimAsString("nonce");
        if (actualNonce == null || expectedNonce == null || !MessageDigest.isEqual(
                actualNonce.getBytes(StandardCharsets.UTF_8), expectedNonce.getBytes(StandardCharsets.UTF_8))) {
            throw new OAuthCallbackException(OAuthCallbackFailure.OAUTH_IDENTITY_VERIFICATION_FAILED);
        }
    }

    private String requiredClaim(String value) {
        if (value == null || value.isBlank()) {
            throw new OAuthCallbackException(OAuthCallbackFailure.OAUTH_IDENTITY_VERIFICATION_FAILED);
        }
        return value;
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new OAuthCallbackException(OAuthCallbackFailure.OAUTH_PROVIDER_UNAVAILABLE);
        }
    }

    private static Duration defaultTimeout(Duration configured, Duration fallback) {
        return configured == null ? fallback : configured;
    }

    private record GoogleTokenResponse(@JsonProperty("id_token") String idToken) {
    }
}
