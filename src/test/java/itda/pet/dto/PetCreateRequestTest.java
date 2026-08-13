package itda.pet.dto;

import static org.assertj.core.api.Assertions.assertThat;

import itda.pet.domain.PetSex;
import itda.pet.domain.PetSizeCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("PetCreateRequest")
class PetCreateRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Nested
    @DisplayName("Describe: Pet 생성 요청을 정규화하고 검증한다")
    class DescribeValidation {

        @Test
        @DisplayName("It: nickname만 trim하고 다른 선택 필드는 그대로 유지한다")
        void itNormalizesOnlyNickname() {
            PetCreateRequest request = request(
                    " 몽이 ",
                    " 견종 ",
                    null,
                    null,
                    null
            );

            assertThat(request.nickname()).isEqualTo("몽이");
            assertThat(request.breedName()).isEqualTo(" 견종 ");
        }

        @Test
        @DisplayName("It: trim 후 공백 nickname과 30자 초과 nickname을 거절한다")
        void itRejectsBlankAndTooLongNickname() {
            assertInvalid(request("   ", null, null, null, null), "nickname");
            assertInvalid(
                    request("가".repeat(31), null, null, null, null),
                    "nickname"
            );
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "몽이😀",
                "몽이❤️",
                "몽이🇰🇷",
                "몽이1️⃣",
                "몽이©",
                "몽이™",
                "몽이☀",
                "몽이♥"
        })
        @DisplayName("It: 이모지와 그림문자 기호가 포함된 nickname을 거절한다")
        void itRejectsEmojiLikeNickname(String nickname) {
            assertInvalid(
                    request(nickname, null, null, null, null),
                    "nickname"
            );
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "몽이",
                "Mung",
                "몽이1",
                "몽이1-",
                "몽 이",
                "Mung 1"
        })
        @DisplayName("It: 일반 문자와 숫자·공백·하이픈은 허용한다")
        void itAllowsOrdinaryNickname(String nickname) {
            assertThat(validator.validate(
                    request(nickname, null, null, null, null)
            )).isEmpty();
        }

        @Test
        @DisplayName("It: 문자열 길이와 personalityTags 개수를 제한한다")
        void itConstrainsStringLengthsAndTagCount() {
            assertInvalid(
                    request("몽이", "a".repeat(101), null, null, null),
                    "breedName"
            );
            assertInvalid(
                    request("몽이", null, "a".repeat(501), null, null),
                    "bio"
            );
            assertInvalid(
                    request(
                            "몽이",
                            null,
                            null,
                            Collections.nCopies(11, "a"),
                            null
                    ),
                    "personalityTags"
            );
            assertInvalid(
                    request("몽이", null, null, null, "a".repeat(501)),
                    "careNote"
            );
        }

        @Test
        @DisplayName("It: personalityTags 내부의 null 요소를 거절한다")
        void itRejectsNullPersonalityTag() {
            PetCreateRequest request = request(
                    "몽이",
                    null,
                    null,
                    Arrays.asList("친화적", null),
                    null
            );

            assertInvalidPathStartsWith(request, "personalityTags");
        }

        @Test
        @DisplayName("It: weightKg의 범위와 소수 둘째 자리 제한을 적용한다")
        void itConstrainsWeight() {
            assertInvalid(
                    requestWithWeight(new BigDecimal("-0.01")),
                    "weightKg"
            );
            assertInvalid(
                    requestWithWeight(new BigDecimal("1000.00")),
                    "weightKg"
            );
            assertInvalid(
                    requestWithWeight(new BigDecimal("1.234")),
                    "weightKg"
            );
            assertThat(validator.validate(
                    requestWithWeight(new BigDecimal("999.99"))
            )).isEmpty();
        }
    }

    private PetCreateRequest request(
            String nickname,
            String breedName,
            String bio,
            List<String> personalityTags,
            String careNote
    ) {
        return new PetCreateRequest(
                nickname,
                breedName,
                PetSex.UNKNOWN,
                true,
                LocalDate.of(2020, 1, 1),
                new BigDecimal("3.25"),
                PetSizeCode.SMALL,
                bio,
                personalityTags,
                careNote,
                null
        );
    }

    private PetCreateRequest requestWithWeight(BigDecimal weightKg) {
        return new PetCreateRequest(
                "몽이",
                null,
                null,
                null,
                null,
                weightKg,
                null,
                null,
                null,
                null,
                null
        );
    }

    private void assertInvalid(PetCreateRequest request, String property) {
        Set<ConstraintViolation<PetCreateRequest>> violations =
                validator.validate(request);
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(property);
    }

    private void assertInvalidPathStartsWith(
            PetCreateRequest request,
            String propertyPrefix
    ) {
        Set<ConstraintViolation<PetCreateRequest>> violations =
                validator.validate(request);
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .anyMatch(path -> path.startsWith(propertyPrefix));
    }
}
