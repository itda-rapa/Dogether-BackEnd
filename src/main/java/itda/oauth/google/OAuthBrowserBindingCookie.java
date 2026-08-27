package itda.oauth.google;

import itda.oauth.service.OAuthOpaqueTokenGenerator;
import itda.oauth.naver.NaverOAuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Shared browser binding cookie. Redis stores only its SHA-256 digest. */
@Component
public class OAuthBrowserBindingCookie {

    public static final String NAME = "__Host-dogether_oauth_browser_binding";

    private final OAuthOpaqueTokenGenerator tokenGenerator;
    private final Duration minimumLifetime;

    /** Kept for focused MVC tests that do not bind both provider configurations. */
    public OAuthBrowserBindingCookie(OAuthOpaqueTokenGenerator tokenGenerator) {
        this.tokenGenerator = tokenGenerator;
        this.minimumLifetime = Duration.ZERO;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OAuthBrowserBindingCookie(
            OAuthOpaqueTokenGenerator tokenGenerator,
            GoogleOAuthProperties googleProperties,
            NaverOAuthProperties naverProperties
    ) {
        this.tokenGenerator = tokenGenerator;
        this.minimumLifetime = max(lifetime(googleProperties.transactionTtl(), googleProperties.transactionGraceTtl()),
                lifetime(naverProperties.transactionTtl(), naverProperties.transactionGraceTtl()));
    }

    public String bindingOrNew(HttpServletRequest request) {
        String existing = binding(request);
        return OAuthAuthorizationTransactionStore.isValidBrowserBinding(existing) ? existing : tokenGenerator.generate();
    }

    public String binding(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    public ResponseCookie cookie(String binding, Duration maxAge) {
        Duration effectiveMaxAge = max(maxAge, minimumLifetime);
        return ResponseCookie.from(NAME, binding).httpOnly(true).secure(true).sameSite("Lax")
                .path("/").maxAge(effectiveMaxAge).build();
    }

    private static Duration lifetime(Duration ttl, Duration grace) {
        return (ttl == null ? Duration.ZERO : ttl).plus(grace == null ? Duration.ZERO : grace);
    }

    private static Duration max(Duration first, Duration second) {
        return first.compareTo(second) >= 0 ? first : second;
    }
}
