package itda.oauth.google;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.oauth.domain.OAuthProvider;
import itda.common.filter.JwtFilter;
import itda.oauth.service.IssuedOAuthLoginCode;
import itda.oauth.service.OAuthLoginCodeIssuer;
import itda.oauth.service.OAuthOpaqueTokenGenerator;
import itda.oauth.service.OAuthVerifiedIdentity;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.dao.DataAccessResourceFailureException;

@WebMvcTest(GoogleOAuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GoogleOAuthControllerTest.OAuthPropertiesConfiguration.class)
class GoogleOAuthControllerTest {

    private static final String BROWSER_BINDING = "A".repeat(43);
    private static final String OTHER_BROWSER_BINDING = "B".repeat(43);

    @Autowired private MockMvc mockMvc;
    @MockitoBean private OAuthAuthorizationTransactionStore transactionStore;
    @MockitoBean private GoogleOidcClient googleOidcClient;
    @MockitoBean private OAuthLoginCodeIssuer loginCodeIssuer;
    @MockitoBean private OAuthOpaqueTokenGenerator tokenGenerator;
    @MockitoBean private JwtFilter jwtFilter;

    @Test
    void authorizationStartUsesOidcBoundaryAndRedirectsBrowser() throws Exception {
        var transaction = new OAuthAuthorizationTransactionStore.CreatedTransaction(
                "state", "verifier", "nonce", Instant.parse("2026-08-25T00:01:00Z"));
        given(tokenGenerator.generate()).willReturn(BROWSER_BINDING);
        given(transactionStore.create(BROWSER_BINDING)).willReturn(transaction);
        given(googleOidcClient.authorizationUri(transaction))
                .willReturn(URI.create("https://accounts.google.com/o/oauth2/v2/auth?state=state"));

        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://accounts.google.com/o/oauth2/v2/auth?state=state"))
                .andExpect(header().string("Set-Cookie",
                        containsString("__Host-dogether_oauth_browser_binding=" + BROWSER_BINDING)))
                .andExpect(header().string("Set-Cookie", containsString("Path=/")))
                .andExpect(header().string("Set-Cookie", containsString("Secure")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=65")))
                .andExpect(header().string("Set-Cookie", not(containsString("Domain="))));

        then(googleOidcClient).should().authorizationUri(transaction);
    }

    @Test
    void authorizationStartReusesExistingValidBrowserBinding() throws Exception {
        var transaction = new OAuthAuthorizationTransactionStore.CreatedTransaction(
                "state", "verifier", "nonce", Instant.parse("2026-08-25T00:01:00Z"));
        given(transactionStore.create(BROWSER_BINDING)).willReturn(transaction);
        given(googleOidcClient.authorizationUri(transaction))
                .willReturn(URI.create("https://accounts.google.com/o/oauth2/v2/auth?state=state"));

        mockMvc.perform(get("/oauth2/authorization/google")
                        .cookie(new Cookie("__Host-dogether_oauth_browser_binding", BROWSER_BINDING)))
                .andExpect(status().isFound())
                .andExpect(header().string("Set-Cookie",
                        containsString("__Host-dogether_oauth_browser_binding=" + BROWSER_BINDING)));

        then(tokenGenerator).shouldHaveNoInteractions();
    }

    @Test
    void authorizationStartReplacesMalformedBrowserBinding() throws Exception {
        var transaction = new OAuthAuthorizationTransactionStore.CreatedTransaction(
                "state", "verifier", "nonce", Instant.parse("2026-08-25T00:01:00Z"));
        given(tokenGenerator.generate()).willReturn(BROWSER_BINDING);
        given(transactionStore.create(BROWSER_BINDING)).willReturn(transaction);
        given(googleOidcClient.authorizationUri(transaction))
                .willReturn(URI.create("https://accounts.google.com/o/oauth2/v2/auth?state=state"));

        mockMvc.perform(get("/oauth2/authorization/google")
                        .cookie(new Cookie("__Host-dogether_oauth_browser_binding", "malformed!")))
                .andExpect(status().isFound())
                .andExpect(header().string("Set-Cookie",
                        containsString("__Host-dogether_oauth_browser_binding=" + BROWSER_BINDING)));
    }

    @Test
    void callbackConsumesStateBeforeExchangingCodeAndRedirectsOnlyWithOpaqueLoginCode() throws Exception {
        var transaction = new OAuthAuthorizationTransactionStore.ConsumedTransaction("verifier", "nonce");
        given(transactionStore.consume("state", BROWSER_BINDING)).willReturn(transaction);
        given(googleOidcClient.exchangeAndVerify("google-code", transaction))
                .willReturn(new OAuthVerifiedIdentity(OAuthProvider.GOOGLE, "google-sub", "user@example.com"));
        given(loginCodeIssuer.issue(any())).willReturn(new IssuedOAuthLoginCode(
                "opaque-login-code", Instant.parse("2026-08-25T00:05:00Z")));

        mockMvc.perform(get("/login/oauth2/code/google")
                        .param("state", "state")
                        .param("code", "google-code")
                        .cookie(new Cookie("__Host-dogether_oauth_browser_binding", BROWSER_BINDING)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://front.example.com/oauth/success?loginCode=opaque-login-code&provider=GOOGLE"));

        then(transactionStore).should().consume("state", BROWSER_BINDING);
        then(googleOidcClient).should().exchangeAndVerify("google-code", transaction);
    }

    @Test
    void callbackWithoutBrowserBindingCannotConsumeAuthorizationTransaction() throws Exception {
        mockMvc.perform(get("/login/oauth2/code/google")
                        .param("state", "state")
                        .param("code", "google-code"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://front.example.com/oauth/error?errorCode=OAUTH_STATE_INVALID"));

        then(transactionStore).shouldHaveNoInteractions();
        then(googleOidcClient).shouldHaveNoInteractions();
        then(loginCodeIssuer).shouldHaveNoInteractions();
    }

    @Test
    void callbackFromDifferentBrowserCannotConsumeAuthorizationTransaction() throws Exception {
        willThrow(new OAuthCallbackException(OAuthCallbackFailure.OAUTH_STATE_INVALID))
                .given(transactionStore).consume("state", OTHER_BROWSER_BINDING);

        mockMvc.perform(get("/login/oauth2/code/google")
                        .param("state", "state")
                        .param("code", "google-code")
                        .cookie(new Cookie("__Host-dogether_oauth_browser_binding", OTHER_BROWSER_BINDING)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://front.example.com/oauth/error?errorCode=OAUTH_STATE_INVALID"));

        then(transactionStore).should().consume("state", OTHER_BROWSER_BINDING);
        then(googleOidcClient).shouldHaveNoInteractions();
        then(loginCodeIssuer).shouldHaveNoInteractions();
    }

    @Test
    void callbackWithMalformedBrowserBindingCannotConsumeAuthorizationTransaction() throws Exception {
        mockMvc.perform(get("/login/oauth2/code/google")
                        .param("state", "state")
                        .param("code", "google-code")
                        .cookie(new Cookie("__Host-dogether_oauth_browser_binding", "malformed!")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://front.example.com/oauth/error?errorCode=OAUTH_STATE_INVALID"));

        then(transactionStore).shouldHaveNoInteractions();
        then(googleOidcClient).shouldHaveNoInteractions();
        then(loginCodeIssuer).shouldHaveNoInteractions();
    }

    @Test
    void providerAuthorizationDenialStillConsumesStateAndUsesAllowlistedErrorRedirect() throws Exception {
        given(transactionStore.consume("state", BROWSER_BINDING))
                .willReturn(new OAuthAuthorizationTransactionStore.ConsumedTransaction("verifier", "nonce"));

        mockMvc.perform(get("/login/oauth2/code/google")
                        .param("state", "state")
                        .param("error", "access_denied")
                        .cookie(new Cookie("__Host-dogether_oauth_browser_binding", BROWSER_BINDING)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://front.example.com/oauth/error?errorCode=OAUTH_AUTHORIZATION_DENIED"));

        then(googleOidcClient).shouldHaveNoInteractions();
        then(loginCodeIssuer).shouldHaveNoInteractions();
    }

    @Test
    void providerAuthorizationDenialWithoutBrowserBindingDoesNotConsumeState() throws Exception {
        mockMvc.perform(get("/login/oauth2/code/google")
                        .param("state", "state")
                        .param("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://front.example.com/oauth/error?errorCode=OAUTH_STATE_INVALID"));

        then(transactionStore).shouldHaveNoInteractions();
    }

    @Test
    void providerUnavailableErrorUsesSafeProviderUnavailableRedirect() throws Exception {
        given(transactionStore.consume("state", BROWSER_BINDING))
                .willReturn(new OAuthAuthorizationTransactionStore.ConsumedTransaction("verifier", "nonce"));

        mockMvc.perform(get("/login/oauth2/code/google")
                        .param("state", "state")
                        .param("error", "temporarily_unavailable")
                        .cookie(new Cookie("__Host-dogether_oauth_browser_binding", BROWSER_BINDING)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://front.example.com/oauth/error?errorCode=OAUTH_PROVIDER_UNAVAILABLE"));
    }

    @Test
    void dataAccessFailureMapsToInternalErrorWithoutLeakingExceptionDetails() throws Exception {
        willThrow(new DataAccessResourceFailureException("redis connection detail"))
                .given(transactionStore).create(BROWSER_BINDING);
        given(tokenGenerator.generate()).willReturn(BROWSER_BINDING);

        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://front.example.com/oauth/error?errorCode=INTERNAL_ERROR"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class OAuthPropertiesConfiguration {
        @Bean
        GoogleOAuthProperties googleOAuthProperties() {
            return new GoogleOAuthProperties(true, "client-id", "client-secret",
                    "https://api.example.com/login/oauth2/code/google",
                    "https://front.example.com/oauth/success",
                    "https://front.example.com/oauth/error",
                    List.of("https://front.example.com/oauth/success", "https://front.example.com/oauth/error"),
                    java.time.Duration.ofMinutes(1), java.time.Duration.ofSeconds(5),
                    java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
        }
    }
}
