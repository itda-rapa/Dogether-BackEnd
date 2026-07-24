package itda.common.security.repository;

import itda.common.security.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select refreshToken
            from RefreshToken refreshToken
            join fetch refreshToken.user
            where refreshToken.tokenHash = :tokenHash
              and refreshToken.revokedAt is null
            """)
    Optional<RefreshToken> findActiveByHashForUpdate(
            @Param("tokenHash") String tokenHash
    );

    @Modifying
    @Query("""
            update RefreshToken refreshToken
               set refreshToken.revokedAt = :revokedAt
             where refreshToken.user.id = :userId
               and refreshToken.revokedAt is null
            """)
    int revokeAllActiveByUserId(
            @Param("userId") Long userId,
            @Param("revokedAt") Instant revokedAt
    );
}
