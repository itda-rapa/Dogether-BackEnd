package itda.common.security.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import itda.common.constants.ErrorCode;
import itda.common.constants.TokenType;
import itda.common.exception.BusinessException;
import itda.common.properties.JwtProperties;
import itda.common.security.TokenHashing;
import itda.common.security.domain.RefreshToken;
import itda.common.security.dto.IssuedTokens;
import itda.common.security.repository.RefreshTokenRepository;
import itda.user.domain.User;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TokenProvider {

    private static final int REFRESH_TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final SecretKey signingKey;

    @Autowired
    public TokenProvider(
            RefreshTokenRepository refreshTokenRepository,
            JwtProperties properties
    ) {
        this(refreshTokenRepository, properties, Clock.systemUTC(), new SecureRandom());
    }

    TokenProvider(
            RefreshTokenRepository refreshTokenRepository,
            JwtProperties properties,
            Clock clock,
            SecureRandom secureRandom
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.properties = properties;
        this.clock = clock;
        this.secureRandom = secureRandom;
        this.signingKey = Keys.hmacShaKeyFor(
                properties.secret().getBytes(StandardCharsets.UTF_8)
        );
    }

    @Transactional
    public IssuedTokens issueTokens(User user) {
        Instant now = clock.instant();
        return issueTokens(user, now);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public IssuedTokens rotateRefreshToken(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshToken refreshToken = refreshTokenRepository
                .findActiveByHashForUpdate(TokenHashing.sha256(rawRefreshToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_EXPIRED));

        if (!refreshToken.isUsableAt(now) || !refreshToken.getUser().isActive()) {
            refreshToken.revoke(now);
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }

        refreshToken.revoke(now);
        return issueTokens(refreshToken.getUser(), now);
    }

    @Transactional
    public void revokeAllForUser(Long userId) {
        refreshTokenRepository.revokeAllActiveByUserId(userId, clock.instant());
    }

    private IssuedTokens issueTokens(User user, Instant now) {
        if (!user.isActive()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        Instant accessTokenExpiresAt = now.plus(properties.accessTtl());
        String accessToken = createAccessToken(user, now);
        String rawRefreshToken = createOpaqueRefreshToken();

        refreshTokenRepository.save(
                RefreshToken.issue(
                        user,
                        TokenHashing.sha256(rawRefreshToken),
                        now.plus(properties.refreshTtl())
                )
        );

        return new IssuedTokens(
                accessToken,
                rawRefreshToken,
                accessTokenExpiresAt
        );
    }

    public Optional<Long> parseActiveUserId(String accessToken) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(accessToken)
                    .getPayload();

            if (!TokenType.ACCESS_TOKEN.name().equals(claims.get("tokenType", String.class))) {
                return Optional.empty();
            }

            return Optional.of(Long.parseLong(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private String createAccessToken(User user, Instant now) {
        return Jwts.builder()
                .subject(user.getId().toString())
                .issuer(properties.issuer())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .claim("tokenType", TokenType.ACCESS_TOKEN.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTtl())))
                .signWith(signingKey)
                .compact();
    }

    private String createOpaqueRefreshToken() {
        byte[] tokenBytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }
}
