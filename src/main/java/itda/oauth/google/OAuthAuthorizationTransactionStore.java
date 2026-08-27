package itda.oauth.google;

import itda.common.security.TokenHashing;
import itda.oauth.domain.OAuthProvider;
import itda.oauth.service.OAuthOpaqueTokenGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** One-time authorization state, held only in Redis until the browser callback is processed. */
@Component
public class OAuthAuthorizationTransactionStore {

    private static final String KEY_PREFIX = "dogether:oauth:v1:";
    private static final Pattern BROWSER_BINDING_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> issueScript;
    private final DefaultRedisScript<List> consumeScript;
    private final OAuthOpaqueTokenGenerator tokenGenerator;
    private final GoogleOAuthProperties properties;
    private final Clock clock;

    @Autowired
    public OAuthAuthorizationTransactionStore(
            @Qualifier("oauthStringRedisTemplate") StringRedisTemplate redis,
            @Qualifier("oauthTransactionIssueScript") DefaultRedisScript<Long> issueScript,
            @Qualifier("oauthTransactionConsumeScript") DefaultRedisScript<List> consumeScript,
            OAuthOpaqueTokenGenerator tokenGenerator,
            GoogleOAuthProperties properties
    ) {
        this(redis, issueScript, consumeScript, tokenGenerator, properties, Clock.systemUTC());
    }

    OAuthAuthorizationTransactionStore(
            StringRedisTemplate redis,
            DefaultRedisScript<Long> issueScript,
            DefaultRedisScript<List> consumeScript,
            OAuthOpaqueTokenGenerator tokenGenerator,
            GoogleOAuthProperties properties,
            Clock clock
    ) {
        this.redis = redis;
        this.issueScript = issueScript;
        this.consumeScript = consumeScript;
        this.tokenGenerator = tokenGenerator;
        this.properties = properties;
        this.clock = clock;
    }

    public CreatedTransaction create(String browserBinding) {
        return create(browserBinding, OAuthProvider.GOOGLE, properties.backendRedirectUri(),
                properties.transactionTtl(), properties.transactionGraceTtl());
    }

    /** Shares only the Redis transaction invariant between providers; Google keeps its required nonce. */
    public CreatedTransaction create(
            String browserBinding,
            OAuthProvider provider,
            java.net.URI backendRedirectUri,
            Duration logicalTtl,
            Duration graceTtl
    ) {
        if (!isValidBrowserBinding(browserBinding)) {
            throw new OAuthCallbackException(OAuthCallbackFailure.OAUTH_STATE_INVALID);
        }
        Instant expiresAt = clock.instant().plus(logicalTtl);
        Duration physicalTtl = logicalTtl.plus(graceTtl);
        for (int attempt = 0; attempt < 3; attempt++) {
            String state = tokenGenerator.generate();
            String codeVerifier = tokenGenerator.generate();
            String nonce = requiresNonce(provider) ? tokenGenerator.generate() : null;
            Long result = redis.execute(issueScript, List.of(key(provider, state)),
                    provider.name(), codeVerifier, nonce == null ? "" : nonce,
                    backendRedirectUri.toString(), Long.toString(expiresAt.toEpochMilli()),
                    TokenHashing.sha256(browserBinding), Long.toString(physicalTtl.toMillis()));
            if (Long.valueOf(1L).equals(result)) {
                return new CreatedTransaction(state, codeVerifier, nonce, expiresAt);
            }
            if (!Long.valueOf(0L).equals(result)) {
                break;
            }
        }
        throw new OAuthCallbackException(OAuthCallbackFailure.OAUTH_PROVIDER_UNAVAILABLE);
    }

    public ConsumedTransaction consume(String state, String browserBinding) {
        return consume(state, browserBinding, OAuthProvider.GOOGLE, properties.backendRedirectUri());
    }

    public ConsumedTransaction consume(
            String state,
            String browserBinding,
            OAuthProvider provider,
            java.net.URI backendRedirectUri
    ) {
        if (state == null || state.isBlank() || !isValidBrowserBinding(browserBinding)) {
            throw new OAuthCallbackException(OAuthCallbackFailure.OAUTH_STATE_INVALID);
        }
        List<?> result = redis.execute(consumeScript, List.of(key(provider, state)),
                Long.toString(clock.instant().toEpochMilli()), provider.name(),
                backendRedirectUri.toString(), TokenHashing.sha256(browserBinding));
        long status = status(result);
        if (status == -2L) {
            throw new OAuthCallbackException(OAuthCallbackFailure.OAUTH_STATE_EXPIRED);
        }
        if (status != 1L || result == null || result.size() != 3) {
            throw new OAuthCallbackException(OAuthCallbackFailure.OAUTH_STATE_INVALID);
        }
        String verifier = text(result.get(1));
        String nonce = text(result.get(2));
        if (verifier == null || (requiresNonce(provider) && nonce == null)) {
            throw new OAuthCallbackException(OAuthCallbackFailure.OAUTH_STATE_INVALID);
        }
        return new ConsumedTransaction(state, verifier, nonce);
    }

    public static String pkceChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static boolean isValidBrowserBinding(String browserBinding) {
        return browserBinding != null && BROWSER_BINDING_PATTERN.matcher(browserBinding).matches();
    }

    private static boolean requiresNonce(OAuthProvider provider) {
        return provider == OAuthProvider.GOOGLE;
    }

    private long status(List<?> result) {
        if (result == null || result.isEmpty() || !(result.getFirst() instanceof Number number)) {
            return -1L;
        }
        return number.longValue();
    }

    private String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private String key(OAuthProvider provider, String state) {
        return KEY_PREFIX + provider.name().toLowerCase(java.util.Locale.ROOT) + ":transaction:"
                + TokenHashing.sha256(state);
    }

    public record CreatedTransaction(String state, String codeVerifier, String nonce, Instant expiresAt) {
        public String codeChallenge() {
            return pkceChallenge(codeVerifier);
        }
    }

    public record ConsumedTransaction(String state, String codeVerifier, String nonce) {
        public ConsumedTransaction(String codeVerifier, String nonce) {
            this(null, codeVerifier, nonce);
        }
    }
}
