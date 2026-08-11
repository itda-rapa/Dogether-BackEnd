package itda.pet.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PetPublicTagGenerator")
class PetPublicTagGeneratorTest {

    private final PetPublicTagGenerator generator = new PetPublicTagGenerator();

    @Test
    @DisplayName("It: 25자 이하 nickname 전체를 PublicTag base로 사용한다")
    void itKeepsShortNicknameAsBase() {
        String nickname = "가".repeat(25);

        String publicTag = generator.generate(nickname);

        assertThat(baseOf(publicTag)).isEqualTo(nickname);
        assertSuffixAndLength(publicTag);
    }

    @Test
    @DisplayName("It: 25자 초과 nickname의 base 길이를 제한한다")
    void itLimitsLongNicknameBaseLength() {
        String nickname = "가".repeat(26);

        String publicTag = generator.generate(nickname);

        assertThat(baseOf(publicTag)).isEqualTo("가".repeat(25));
        assertSuffixAndLength(publicTag);
    }

    private String baseOf(String publicTag) {
        return publicTag.substring(0, publicTag.lastIndexOf('#'));
    }

    private void assertSuffixAndLength(String publicTag) {
        String suffix = publicTag.substring(publicTag.lastIndexOf('#') + 1);
        assertThat(suffix).matches("[A-Z0-9]{4}");
        assertThat(publicTag.codePointCount(0, publicTag.length()))
                .isLessThanOrEqualTo(30);
    }
}
