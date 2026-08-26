package itda.oauth.repository;

import itda.oauth.domain.OAuthIdentity;
import itda.oauth.domain.OAuthProvider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OAuthIdentityRepository extends JpaRepository<OAuthIdentity, Long> {

    @Query("""
            select identity
            from OAuthIdentity identity
            join fetch identity.user
            where identity.provider = :provider
              and identity.providerSubject = :providerSubject
            """)
    Optional<OAuthIdentity> findWithUserByProviderAndProviderSubject(
            @Param("provider") OAuthProvider provider,
            @Param("providerSubject") String providerSubject
    );

    boolean existsByProviderAndProviderSubject(
            OAuthProvider provider,
            String providerSubject
    );
}
