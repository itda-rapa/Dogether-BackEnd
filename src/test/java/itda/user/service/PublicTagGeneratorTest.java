package itda.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import itda.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PublicTagGeneratorTest {

    @Mock
    private UserRepository userRepository;

    @Test
    void generatesNicknameAndFourCharacterSuffix() {
        given(userRepository.existsByPublicTag(anyString())).willReturn(false);

        String publicTag = new PublicTagGenerator(userRepository)
                .generate("초코아빠");

        assertThat(publicTag).matches("초코아빠#[A-Z2-9]{4}");
    }
}
