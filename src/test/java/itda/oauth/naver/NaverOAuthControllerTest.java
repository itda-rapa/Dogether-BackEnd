package itda.oauth.naver;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.common.filter.JwtFilter;
import itda.oauth.domain.OAuthProvider;
import itda.oauth.google.OAuthAuthorizationTransactionStore;
import itda.oauth.google.OAuthBrowserBindingCookie;
import itda.oauth.service.IssuedOAuthLoginCode;
import itda.oauth.service.OAuthLoginCodeIssuer;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@WebMvcTest(NaverOAuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@org.springframework.context.annotation.Import(NaverOAuthControllerTest.PropertiesConfiguration.class)
class NaverOAuthControllerTest {
    private static final String BINDING = "A".repeat(43);

    @Autowired private MockMvc mockMvc;
    @MockitoBean private OAuthAuthorizationTransactionStore transactionStore;
    @MockitoBean private NaverOidcClient naverOidcClient;
    @MockitoBean private OAuthLoginCodeIssuer loginCodeIssuer;
    @MockitoBean private OAuthBrowserBindingCookie browserBindingCookie;
    @MockitoBean private JwtFilter jwtFilter;

    @Test
    void authorizationStartCreatesNaverNonceFreeTransactionAndUsesSharedBindingCookie() throws Exception {
        var transaction = new OAuthAuthorizationTransactionStore.CreatedTransaction("state", "verifier", null, Instant.now().plusSeconds(60));
        given(browserBindingCookie.bindingOrNew(org.mockito.ArgumentMatchers.any())).willReturn(BINDING);
        given(transactionStore.create(BINDING, OAuthProvider.NAVER, URI.create("https://api.example.com/login/oauth2/code/naver"),
                Duration.ofMinutes(1), Duration.ofSeconds(5))).willReturn(transaction);
        given(naverOidcClient.authorizationUri(transaction)).willReturn(URI.create("https://nid.naver.com/oauth2/authorize?state=state"));
        given(browserBindingCookie.cookie(BINDING, Duration.ofSeconds(65))).willReturn(ResponseCookie.from("__Host-dogether_oauth_browser_binding", BINDING).path("/").build());

        mockMvc.perform(get("/oauth2/authorization/naver"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://nid.naver.com/oauth2/authorize?state=state"));

        then(transactionStore).should().create(BINDING, OAuthProvider.NAVER, URI.create("https://api.example.com/login/oauth2/code/naver"),
                Duration.ofMinutes(1), Duration.ofSeconds(5));
    }

    @Test
    void callbackUsesNaverTransactionAndOnlyReturnsLoginCodeAndProvider() throws Exception {
        var transaction = new OAuthAuthorizationTransactionStore.ConsumedTransaction("state", "verifier", null);
        var identity = new itda.oauth.service.OAuthVerifiedIdentity(OAuthProvider.NAVER, "sub", "person@example.com");
        given(browserBindingCookie.binding(org.mockito.ArgumentMatchers.any())).willReturn(BINDING);
        given(transactionStore.consume("state", BINDING, OAuthProvider.NAVER, URI.create("https://api.example.com/login/oauth2/code/naver"))).willReturn(transaction);
        given(naverOidcClient.exchangeAndVerify("code", transaction)).willReturn(identity);
        given(loginCodeIssuer.issue(identity)).willReturn(new IssuedOAuthLoginCode("login-code", Instant.now().plusSeconds(60)));

        mockMvc.perform(get("/login/oauth2/code/naver").param("state", "state").param("code", "code"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://front.example.com/oauth/success?loginCode=login-code&provider=NAVER"));
    }

    @Test
    void disabledEndpointsReturnNotFoundWithoutTouchingOAuthDependencies() throws Exception {
        OAuthAuthorizationTransactionStore store = mock(OAuthAuthorizationTransactionStore.class);
        NaverOidcClient client = mock(NaverOidcClient.class);
        OAuthLoginCodeIssuer issuer = mock(OAuthLoginCodeIssuer.class);
        OAuthBrowserBindingCookie cookie = mock(OAuthBrowserBindingCookie.class);
        MockMvc disabledMvc = MockMvcBuilders.standaloneSetup(new NaverOAuthController(disabledProperties(), store, client, issuer, cookie)).build();

        disabledMvc.perform(get("/oauth2/authorization/naver")).andExpect(status().isNotFound());
        disabledMvc.perform(get("/login/oauth2/code/naver").param("state", "state").param("code", "code"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(store, client, issuer, cookie);
    }

    @Test
    void callbackWithoutValidBindingDoesNotConsumeStateOrCallNaver() throws Exception {
        given(browserBindingCookie.binding(org.mockito.ArgumentMatchers.any())).willReturn(null);

        mockMvc.perform(get("/login/oauth2/code/naver").param("state", "state").param("code", "code"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://front.example.com/oauth/error?errorCode=OAUTH_STATE_INVALID"));

        then(transactionStore).shouldHaveNoInteractions();
        then(naverOidcClient).shouldHaveNoInteractions();
        then(loginCodeIssuer).shouldHaveNoInteractions();
    }

    @Test
    void authorizationDenialConsumesStateButDoesNotExposeProviderDescriptionOrCallNaver() throws Exception {
        given(browserBindingCookie.binding(org.mockito.ArgumentMatchers.any())).willReturn(BINDING);
        given(transactionStore.consume("state", BINDING, OAuthProvider.NAVER,
                URI.create("https://api.example.com/login/oauth2/code/naver")))
                .willReturn(new OAuthAuthorizationTransactionStore.ConsumedTransaction("state", "verifier", null));

        mockMvc.perform(get("/login/oauth2/code/naver").param("state", "state")
                        .param("error", "access_denied").param("error_description", "private provider details"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://front.example.com/oauth/error?errorCode=OAUTH_AUTHORIZATION_DENIED"));

        then(naverOidcClient).shouldHaveNoInteractions();
        then(loginCodeIssuer).shouldHaveNoInteractions();
    }

    private NaverOAuthProperties disabledProperties() {
        return new NaverOAuthProperties(false, null, null, null, null, null, List.of(),
                Duration.ofMinutes(1), Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PropertiesConfiguration {
        @Bean NaverOAuthProperties naverOAuthProperties() {
            return new NaverOAuthProperties(true, "client-id", "client-secret", "https://api.example.com/login/oauth2/code/naver",
                    "https://front.example.com/oauth/success", "https://front.example.com/oauth/error",
                    List.of("https://front.example.com/oauth/success", "https://front.example.com/oauth/error"),
                    Duration.ofMinutes(1), Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(1));
        }
    }
}
