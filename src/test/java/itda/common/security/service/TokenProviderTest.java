package itda.common.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import itda.common.constants.TokenType;
import itda.common.properties.JwtProperties;
import itda.common.security.repository.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class TokenProviderTest {

    private static final Instant NOW = Instant.parse("2030-08-07T06:00:00Z");
    private static final String ISSUER = "dogether-test";
    private static final String SECRET = "01234567890123456789012345678901";

    @Test
    void parsesTheSameAccessTokenForUserIdAndSession() {
        JwtProperties properties = new JwtProperties(
                ISSUER,
                SECRET,
                Duration.ofMinutes(15),
                Duration.ofDays(30)
        );
        TokenProvider tokenProvider = new TokenProvider(
                mock(RefreshTokenRepository.class),
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new java.security.SecureRandom()
        );
        SecretKey signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant expiresAt = NOW.plusSeconds(900);
        String accessToken = Jwts.builder()
                .subject("42")
                .issuer(ISSUER)
                .claim("tokenType", TokenType.ACCESS_TOKEN.name())
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();

        assertThat(tokenProvider.parseActiveUserId(accessToken))
                .contains(42L);
        assertThat(tokenProvider.parseAccessTokenSession(accessToken))
                .contains(new AccessTokenSession(42L, expiresAt));
    }
}
