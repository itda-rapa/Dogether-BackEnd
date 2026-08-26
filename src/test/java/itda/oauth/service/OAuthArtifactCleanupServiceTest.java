package itda.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import itda.oauth.repository.OAuthLoginCodeRepository;
import itda.oauth.repository.OAuthSignupTokenRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OAuthArtifactCleanupServiceTest {

    @Mock private OAuthLoginCodeRepository loginCodeRepository;
    @Mock private OAuthSignupTokenRepository signupTokenRepository;

    @Test
    void deletesBothArtifactKindsOnlyAfterPhysicalCleanupGrace() {
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        Instant cleanupCutoff = now.minus(OAuthArtifactCleanupService.PHYSICAL_CLEANUP_GRACE);
        given(loginCodeRepository.deleteExpiredBefore(cleanupCutoff)).willReturn(2);
        given(signupTokenRepository.deleteExpiredBefore(cleanupCutoff)).willReturn(3);
        OAuthArtifactCleanupService service = new OAuthArtifactCleanupService(
                loginCodeRepository, signupTokenRepository, Clock.fixed(now, ZoneOffset.UTC));

        assertThat(service.deleteExpiredArtifacts()).isEqualTo(5);

        then(loginCodeRepository).should().deleteExpiredBefore(cleanupCutoff);
        then(signupTokenRepository).should().deleteExpiredBefore(cleanupCutoff);
    }
}
