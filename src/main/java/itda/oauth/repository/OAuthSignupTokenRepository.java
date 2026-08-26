package itda.oauth.repository;

import itda.oauth.domain.OAuthSignupToken;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OAuthSignupTokenRepository extends JpaRepository<OAuthSignupToken, Long> {

    Optional<OAuthSignupToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from OAuthSignupToken token where token.tokenHash = :tokenHash")
    Optional<OAuthSignupToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("delete from OAuthSignupToken token where token.expiresAt < :now")
    int deleteExpiredBefore(@Param("now") Instant now);
}
