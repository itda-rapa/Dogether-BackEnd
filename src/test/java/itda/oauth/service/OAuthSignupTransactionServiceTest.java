package itda.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.neighborhood.repository.NeighborhoodRepository;
import itda.oauth.repository.OAuthIdentityRepository;
import itda.oauth.repository.OAuthSignupTokenRepository;
import itda.user.repository.UserRepository;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OAuthSignupTransactionServiceTest {

    @Test
    void acceptsTrimmedNicknameAtTwoAndTwentyCharactersButRejectsTwentyOne() {
        OAuthSignupTransactionService service = new OAuthSignupTransactionService(
                org.mockito.Mockito.mock(OAuthSignupTokenRepository.class),
                org.mockito.Mockito.mock(OAuthIdentityRepository.class),
                org.mockito.Mockito.mock(UserRepository.class),
                org.mockito.Mockito.mock(NeighborhoodRepository.class), Clock.systemUTC());

        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeNickname", " 가나 "))
                .isEqualTo("가나");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeNickname", "가".repeat(20)))
                .isEqualTo("가".repeat(20));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service, "normalizeNickname", "가".repeat(21)))
                .isInstanceOf(OAuthFlowException.class);
    }
}
