package itda.oauth.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.oauth.service.OAuthOpaqueTokenGenerator;
import itda.common.security.TokenHashing;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("redis")
@Testcontainers
class OAuthAuthorizationTransactionStoreIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory factory;

    @AfterEach
    void tearDown() {
        if (factory != null) factory.destroy();
    }

    @Test
    void stateCanBeConsumedExactlyOnceAcrossConcurrentCallbacks() throws Exception {
        OAuthAuthorizationTransactionStore store = store(Instant.parse("2026-08-25T00:00:00Z"));
        var created = store.create();
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> consume = () -> {
                try {
                    store.consume(created.state());
                    return true;
                } catch (OAuthCallbackException exception) {
                    return false;
                }
            };
            long winners = executor.invokeAll(List.of(consume, consume)).stream()
                    .filter(result -> {
                        try {
                            return result.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .count();

            assertThat(winners).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredStateIsDeletedAndReportedAsExpired() {
        Instant start = Instant.parse("2026-08-25T00:00:00Z");
        OAuthAuthorizationTransactionStore creator = store(start);
        var created = creator.create();
        OAuthAuthorizationTransactionStore afterExpiry = store(start.plusSeconds(61));

        assertThatThrownBy(() -> afterExpiry.consume(created.state()))
                .isInstanceOf(OAuthCallbackException.class)
                .extracting(error -> ((OAuthCallbackException) error).failure())
                .isEqualTo(OAuthCallbackFailure.OAUTH_STATE_EXPIRED);
        assertThatThrownBy(() -> afterExpiry.consume(created.state()))
                .isInstanceOf(OAuthCallbackException.class)
                .extracting(error -> ((OAuthCallbackException) error).failure())
                .isEqualTo(OAuthCallbackFailure.OAUTH_STATE_INVALID);
    }

    @Test
    void pkceChallengeUsesRfc7636S256Base64UrlEncoding() {
        assertThat(OAuthAuthorizationTransactionStore.pkceChallenge(
                "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    @Test
    void redirectBindingMismatchInvalidatesAndDeletesState() {
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        OAuthAuthorizationTransactionStore creator = store(now, properties());
        var created = creator.create();
        GoogleOAuthProperties differentRedirect = new GoogleOAuthProperties(true, "client-id", "client-secret",
                "https://other-api.example.com/login/oauth2/code/google",
                "https://front.example.com/oauth/success", "https://front.example.com/oauth/error",
                List.of("https://front.example.com/oauth/success", "https://front.example.com/oauth/error"),
                Duration.ofSeconds(60), Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThatThrownBy(() -> store(now, differentRedirect).consume(created.state()))
                .isInstanceOf(OAuthCallbackException.class)
                .extracting(error -> ((OAuthCallbackException) error).failure())
                .isEqualTo(OAuthCallbackFailure.OAUTH_STATE_INVALID);
        assertThat(template().hasKey(key(created.state()))).isFalse();
    }

    @Test
    void corruptVerifierOrNonceInvalidatesAndDeletesState() {
        OAuthAuthorizationTransactionStore store = store(Instant.parse("2026-08-25T00:00:00Z"));
        var missingVerifier = store.create();
        template().opsForHash().delete(key(missingVerifier.state()), "codeVerifier");
        assertInvalid(store, missingVerifier.state());

        var missingNonce = store.create();
        template().opsForHash().delete(key(missingNonce.state()), "nonce");
        assertInvalid(store, missingNonce.state());
        assertThat(template().hasKey(key(missingVerifier.state()))).isFalse();
        assertThat(template().hasKey(key(missingNonce.state()))).isFalse();
    }

    @Test
    void physicalTtlIncludesConfiguredGraceWhileLogicalExpiryStillFailsClosed() {
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        OAuthAuthorizationTransactionStore creator = store(now);
        var created = creator.create();
        assertThat(template().getExpire(key(created.state()), java.util.concurrent.TimeUnit.MILLISECONDS))
                .isBetween(59_000L, 65_000L);

        assertThatThrownBy(() -> store(now.plusSeconds(60)).consume(created.state()))
                .isInstanceOf(OAuthCallbackException.class)
                .extracting(error -> ((OAuthCallbackException) error).failure())
                .isEqualTo(OAuthCallbackFailure.OAUTH_STATE_EXPIRED);
    }

    private OAuthAuthorizationTransactionStore store(Instant now) {
        return store(now, properties());
    }

    private OAuthAuthorizationTransactionStore store(Instant now, GoogleOAuthProperties properties) {
        return new OAuthAuthorizationTransactionStore(template(), script("redis/oauth-transaction-issue.lua", Long.class),
                script("redis/oauth-transaction-consume.lua", List.class), new OAuthOpaqueTokenGenerator(),
                properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    private StringRedisTemplate template() {
        if (factory == null) {
            RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                    REDIS.getHost(), REDIS.getMappedPort(6379));
            configuration.setDatabase(6);
            factory = new LettuceConnectionFactory(configuration);
            factory.afterPropertiesSet();
        }
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    private <T> DefaultRedisScript<T> script(String path, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(type);
        return script;
    }

    private GoogleOAuthProperties properties() {
        return new GoogleOAuthProperties(true, "client-id", "client-secret",
                URI.create("https://api.example.com/login/oauth2/code/google").toString(),
                URI.create("https://front.example.com/oauth/success").toString(),
                URI.create("https://front.example.com/oauth/error").toString(),
                List.of("https://front.example.com/oauth/success", "https://front.example.com/oauth/error"),
                Duration.ofSeconds(60), Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    private String key(String rawState) {
        return "dogether:oauth:v1:google:transaction:" + TokenHashing.sha256(rawState);
    }

    private void assertInvalid(OAuthAuthorizationTransactionStore store, String state) {
        assertThatThrownBy(() -> store.consume(state))
                .isInstanceOf(OAuthCallbackException.class)
                .extracting(error -> ((OAuthCallbackException) error).failure())
                .isEqualTo(OAuthCallbackFailure.OAUTH_STATE_INVALID);
    }
}
