package itda.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenHashingTest {

    @Test
    void sha256ReturnsStableLowercaseHexWithoutKeepingRawToken() {
        String rawToken = "refresh-token";

        String first = TokenHashing.sha256(rawToken);
        String second = TokenHashing.sha256(rawToken);

        assertThat(first)
                .isEqualTo(second)
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .doesNotContain(rawToken);
    }
}
