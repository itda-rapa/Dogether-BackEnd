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

/**
 * {@code NOW} sits in the future on purpose. JJWT validates {@code exp} against the system clock
 * while {@link TokenProvider#parseAccessTokenSession} validates it against the injected one, and
 * only a gap between the two makes that difference observable.
 */
class TokenProviderTest {

    private static final Instant NOW = Instant.parse("2030-08-07T06:00:00Z");
    private static final String ISSUER = "dogether-test";
    private static final String SECRET = "01234567890123456789012345678901";
    private static final String OTHER_SECRET = "abcdefghijabcdefghijabcdefghijab";

    private static final SecretKey SIGNING_KEY =
            Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private final TokenProvider tokenProvider = newTokenProvider();

    // ---------- both parsers agree ----------

    @Test
    void parsesTheSameAccessTokenForUserIdAndSession() {
        Instant expiresAt = NOW.plusSeconds(900);
        String accessToken = accessToken(builder -> builder.expiration(Date.from(expiresAt)));

        assertThat(tokenProvider.parseActiveUserId(accessToken)).contains(42L);
        assertThat(tokenProvider.parseAccessTokenSession(accessToken))
                .contains(new AccessTokenSession(42L, expiresAt));
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejectedByBothParsers() {
        String forged = Jwts.builder()
                .subject("42")
                .issuer(ISSUER)
                .claim("tokenType", TokenType.ACCESS_TOKEN.name())
                .expiration(Date.from(NOW.plusSeconds(900)))
                .signWith(Keys.hmacShaKeyFor(OTHER_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertBothReject(forged);
    }

    @Test
    void aTokenFromAnotherIssuerIsRejectedByBothParsers() {
        assertBothReject(accessToken(builder -> builder
                .issuer("someone-else")
                .expiration(Date.from(NOW.plusSeconds(900)))));
    }

    @Test
    void aRefreshTokenIsRejectedByBothParsers() {
        assertBothReject(accessToken(builder -> builder
                .claim("tokenType", TokenType.REFRESH_TOKEN.name())
                .expiration(Date.from(NOW.plusSeconds(900)))));
    }

    @Test
    void aTokenWithoutTokenTypeIsRejectedByBothParsers() {
        String noType = Jwts.builder()
                .subject("42")
                .issuer(ISSUER)
                .expiration(Date.from(NOW.plusSeconds(900)))
                .signWith(SIGNING_KEY)
                .compact();

        assertBothReject(noType);
    }

    @Test
    void aTokenExpiredAgainstTheSystemClockIsRejectedByBothParsers() {
        assertBothReject(accessToken(builder -> builder
                .expiration(Date.from(Instant.parse("2020-01-01T00:00:00Z")))));
    }

    @Test
    void aNonNumericSubjectIsRejectedByBothParsers() {
        assertBothReject(accessToken(builder -> builder
                .subject("not-a-user-id")
                .expiration(Date.from(NOW.plusSeconds(900)))));
    }

    @Test
    void aTokenWithoutASubjectIsRejectedByBothParsers() {
        String noSubject = Jwts.builder()
                .issuer(ISSUER)
                .claim("tokenType", TokenType.ACCESS_TOKEN.name())
                .expiration(Date.from(NOW.plusSeconds(900)))
                .signWith(SIGNING_KEY)
                .compact();

        assertBothReject(noSubject);
    }

    @Test
    void garbageIsRejectedByBothParsers() {
        assertBothReject("not.a.jwt");
    }

    // ---------- the two parsers deliberately differ ----------

    /**
     * {@code parseActiveUserId} leaves expiry to JJWT, which reads the system clock.
     * {@code parseAccessTokenSession} re-checks against the injected clock because the session it
     * returns is used to schedule a socket close — that decision must not depend on a clock the
     * application does not control.
     */
    @Test
    void onlyTheSessionParserHonoursTheInjectedClockForExpiry() {
        String expiredForInjectedClockOnly = accessToken(builder -> builder
                .expiration(Date.from(NOW.minusSeconds(60))));

        assertThat(tokenProvider.parseActiveUserId(expiredForInjectedClockOnly)).contains(42L);
        assertThat(tokenProvider.parseAccessTokenSession(expiredForInjectedClockOnly)).isEmpty();
    }

    /**
     * A token with no {@code exp} at all passes JJWT untouched, so the user-id parser accepts it.
     * The session parser cannot — there would be no instant to schedule the close for.
     */
    @Test
    void onlyTheSessionParserRequiresAnExpiryClaim() {
        String withoutExpiry = Jwts.builder()
                .subject("42")
                .issuer(ISSUER)
                .claim("tokenType", TokenType.ACCESS_TOKEN.name())
                .signWith(SIGNING_KEY)
                .compact();

        assertThat(tokenProvider.parseActiveUserId(withoutExpiry)).contains(42L);
        assertThat(tokenProvider.parseAccessTokenSession(withoutExpiry)).isEmpty();
    }

    /**
     * The boundary is exclusive: a token expiring exactly now is already unusable, so a session
     * can never be created with a zero-length remaining lifetime.
     */
    @Test
    void anExpiryExactlyAtTheInjectedNowIsNotUsable() {
        String expiringNow = accessToken(builder -> builder.expiration(Date.from(NOW)));

        assertThat(tokenProvider.parseAccessTokenSession(expiringNow)).isEmpty();
    }

    // ---------- helpers ----------

    private void assertBothReject(String token) {
        assertThat(tokenProvider.parseActiveUserId(token)).isEmpty();
        assertThat(tokenProvider.parseAccessTokenSession(token)).isEmpty();
    }

    private String accessToken(java.util.function.UnaryOperator<io.jsonwebtoken.JwtBuilder> customizer) {
        io.jsonwebtoken.JwtBuilder builder = Jwts.builder()
                .subject("42")
                .issuer(ISSUER)
                .claim("tokenType", TokenType.ACCESS_TOKEN.name())
                .issuedAt(Date.from(NOW));
        return customizer.apply(builder).signWith(SIGNING_KEY).compact();
    }

    private static TokenProvider newTokenProvider() {
        JwtProperties properties = new JwtProperties(
                ISSUER,
                SECRET,
                Duration.ofMinutes(15),
                Duration.ofDays(30)
        );
        return new TokenProvider(
                mock(RefreshTokenRepository.class),
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new java.security.SecureRandom()
        );
    }
}
