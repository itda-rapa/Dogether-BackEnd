package itda.oauth.naver;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class NaverOAuthPropertiesTest {

    @Test
    void rejectsTransactionTtlThatRoundsDownToZeroMilliseconds() {
        assertThatThrownBy(() -> validate(properties(Duration.ofNanos(1), Duration.ZERO)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction-ttl");
    }

    @Test
    void rejectsNullOrNegativeGraceTtlLikeGoogleProperties() {
        assertThatThrownBy(() -> validate(properties(Duration.ofSeconds(1), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction-grace-ttl");
        assertThatThrownBy(() -> validate(properties(Duration.ofSeconds(1), Duration.ofMillis(-1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction-grace-ttl");
    }

    private void validate(NaverOAuthProperties properties) {
        ReflectionTestUtils.invokeMethod(properties, "validateWhenEnabled");
    }

    private NaverOAuthProperties properties(Duration ttl, Duration grace) {
        return new NaverOAuthProperties(true, "client-id", "client-secret",
                "https://api.example.com/login/oauth2/code/naver", "https://front.example.com/oauth/success",
                "https://front.example.com/oauth/error", List.of("https://front.example.com/oauth/success", "https://front.example.com/oauth/error"),
                ttl, grace, Duration.ofSeconds(1), Duration.ofSeconds(1));
    }
}
