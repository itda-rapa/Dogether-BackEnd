package itda.oauth.naver;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.oauth.naver")
public record NaverOAuthProperties(
        boolean enabled,
        String clientId,
        String clientSecret,
        String redirectUri,
        String successCallbackUri,
        String errorCallbackUri,
        List<String> allowedCallbackUrls,
        Duration transactionTtl,
        Duration transactionGraceTtl,
        Duration connectTimeout,
        Duration readTimeout
) {

    @PostConstruct
    void validateWhenEnabled() {
        if (!enabled) return;
        requireText(clientId, "client-id");
        requireText(clientSecret, "client-secret");
        validateBackendRedirect(redirectUri);
        validateCallback(successCallbackUri, "success-callback-uri");
        validateCallback(errorCallbackUri, "error-callback-uri");
        if (allowedCallbackUrls == null || allowedCallbackUrls.isEmpty()) {
            throw invalid("allowed-callback-urls must contain the configured callback URLs");
        }
        List<String> allowlist = allowedCallbackUrls.stream().filter(value -> value != null && !value.isBlank()).toList();
        if (!allowlist.contains(successCallbackUri) || !allowlist.contains(errorCallbackUri)) {
            throw invalid("configured callback URLs must be in allowed-callback-urls");
        }
        for (String callback : allowlist) validateCallback(callback, "allowed-callback-urls");
        if (transactionTtl == null || transactionTtl.toMillis() <= 0) {
            throw invalid("transaction-ttl must be positive");
        }
        if (transactionGraceTtl == null || transactionGraceTtl.isNegative()
                || transactionTtl.plus(transactionGraceTtl).toMillis() <= 0) {
            throw invalid("transaction-grace-ttl must not be negative");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw invalid("HTTP timeouts must be positive");
        }
    }

    public URI backendRedirectUri() { validateBackendRedirect(redirectUri); return URI.create(redirectUri); }
    public URI successCallback() { validateConfiguredCallback(successCallbackUri); return URI.create(successCallbackUri); }
    public URI errorCallback() { validateConfiguredCallback(errorCallbackUri); return URI.create(errorCallbackUri); }

    private void validateConfiguredCallback(String value) {
        validateCallback(value, "callback URI");
        if (allowedCallbackUrls == null || !allowedCallbackUrls.contains(value)) throw invalid("callback URI is not allowlisted");
    }
    private static void validateBackendRedirect(String value) {
        URI uri = parse(value, "redirect-uri");
        if (!isSecureOrExplicitLocalhost(uri)) throw invalid("redirect-uri must use HTTPS, except explicit localhost HTTP");
        if (!"/login/oauth2/code/naver".equals(uri.getPath())) throw invalid("redirect-uri must target /login/oauth2/code/naver");
    }
    private static void validateCallback(String value, String name) {
        URI uri = parse(value, name);
        if (!isSecureOrExplicitLocalhost(uri)) throw invalid(name + " must use HTTPS, except explicit localhost HTTP");
    }
    private static URI parse(String value, String name) {
        requireText(value, name);
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getRawQuery() != null) {
                throw invalid(name + " must be an absolute origin/path URL without query, fragment, or user info");
            }
            return uri;
        } catch (IllegalArgumentException exception) { throw invalid(name + " is not a valid URI"); }
    }
    private static boolean isSecureOrExplicitLocalhost(URI uri) {
        if ("https".equalsIgnoreCase(uri.getScheme())) return true;
        String host = uri.getHost();
        return "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host));
    }
    private static void requireText(String value, String name) { if (value == null || value.isBlank()) throw invalid(name + " is required when Naver OAuth is enabled"); }
    private static IllegalStateException invalid(String message) { return new IllegalStateException("Invalid Naver OAuth configuration: " + message); }
}
