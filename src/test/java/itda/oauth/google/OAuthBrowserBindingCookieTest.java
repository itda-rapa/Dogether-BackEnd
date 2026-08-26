package itda.oauth.google;

import static org.assertj.core.api.Assertions.assertThat;

import itda.oauth.naver.NaverOAuthProperties;
import itda.oauth.service.OAuthOpaqueTokenGenerator;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class OAuthBrowserBindingCookieTest {

    @Test
    void sharedCookieNeverShortensAnExistingProviderTransaction() {
        OAuthBrowserBindingCookie cookie = new OAuthBrowserBindingCookie(new OAuthOpaqueTokenGenerator(),
                googleProperties(Duration.ofMinutes(10), Duration.ofMinutes(1)),
                naverProperties(Duration.ofMinutes(1), Duration.ofMinutes(1)));

        String setCookie = cookie.cookie("A".repeat(43), Duration.ofMinutes(2)).toString();

        assertThat(setCookie).contains("Max-Age=660");
    }

    private GoogleOAuthProperties googleProperties(Duration ttl, Duration grace) {
        return new GoogleOAuthProperties(false, null, null, null, null, null, List.of(), ttl, grace, Duration.ofSeconds(1), Duration.ofSeconds(1));
    }
    private NaverOAuthProperties naverProperties(Duration ttl, Duration grace) {
        return new NaverOAuthProperties(false, null, null, null, null, null, List.of(), ttl, grace, Duration.ofSeconds(1), Duration.ofSeconds(1));
    }
}
