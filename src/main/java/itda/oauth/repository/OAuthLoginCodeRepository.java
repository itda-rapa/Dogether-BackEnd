package itda.oauth.repository;

import itda.oauth.domain.OAuthLoginCode;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OAuthLoginCodeRepository extends JpaRepository<OAuthLoginCode, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select code from OAuthLoginCode code where code.tokenHash = :tokenHash")
    Optional<OAuthLoginCode> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("delete from OAuthLoginCode code where code.expiresAt < :now")
    int deleteExpiredBefore(@Param("now") Instant now);
}
