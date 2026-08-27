package itda.user.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

@DisplayName("MeUpdateRequestParser")
class MeUpdateRequestParserTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final MeUpdateRequestParser parser = new MeUpdateRequestParser();

    @Nested
    @DisplayName("Describe: PATCH /me의 tri-state와 허용 필드")
    class DescribePresenceAndAllowedFields {

        @Test
        @DisplayName("It: 생략과 explicit null을 구분해 보존한다")
        void preservesOmittedAndExplicitNull() throws Exception {
            MeUpdateCommand command = parser.parse(node("{\"weightKg\":null}"));

            assertThat(command.nicknamePresent()).isFalse();
            assertThat(command.neighborhoodCodePresent()).isFalse();
            assertThat(command.weightKgPresent()).isTrue();
            assertThat(command.weightKg()).isNull();
        }

        @ParameterizedTest
        @MethodSource("itda.user.dto.MeUpdateRequestParserTest#invalidStructureBodies")
        @DisplayName("It: 빈 객체, unknown 혼합, non-object root를 모두 거절한다")
        void rejectsInvalidStructure(String json) throws Exception {
            assertValidationFailed(() -> parser.parse(node(json)));
        }
    }

    @Nested
    @DisplayName("Describe: PATCH /me의 엄격한 wire 타입")
    class DescribeStrictWireTypes {

        @ParameterizedTest
        @MethodSource("itda.user.dto.MeUpdateRequestParserTest#invalidTypedBodies")
        @DisplayName("It: 문자열 숫자, coercion, 소수 셋째 자리를 거절한다")
        void rejectsCoercionAndInvalidWeights(String json) throws Exception {
            assertValidationFailed(() -> parser.parse(node(json)));
        }

        @Test
        @DisplayName("It: 공백은 문자열 필드만 trim하고 숫자 값을 정확히 전달한다")
        void parsesStrictValidValues() throws Exception {
            MeUpdateCommand command = parser.parse(node("""
                    {
                      "nickname":"  새이름  ",
                      "neighborhoodCode":"  4113111500  ",
                      "weightKg":72.50
                    }
                    """));

            assertThat(command.nickname()).isEqualTo("새이름");
            assertThat(command.neighborhoodCode()).isEqualTo("4113111500");
            assertThat(command.weightKg()).isEqualByComparingTo("72.50");
        }
    }

    static Stream<String> invalidStructureBodies() {
        return Stream.of(
                "{}", "{\"unknown\":1}", "{\"nickname\":\"새이름\",\"unknown\":1}",
                "null", "[]", "\"value\"", "1", "true"
        );
    }

    static Stream<String> invalidTypedBodies() {
        return Stream.of(
                "{\"nickname\":null}", "{\"nickname\":1}", "{\"nickname\":\" \"}",
                "{\"neighborhoodCode\":null}", "{\"neighborhoodCode\":false}",
                "{\"neighborhoodCode\":\"   \"}",
                "{\"neighborhoodCode\":\"123456789012345678901\"}",
                "{\"weightKg\":\"72.50\"}", "{\"weightKg\":true}",
                "{\"weightKg\":72.501}", "{\"weightKg\":0.99}", "{\"weightKg\":500.01}"
        );
    }

    private static tools.jackson.databind.JsonNode node(String json) throws Exception {
        return OBJECT_MAPPER.readTree(json);
    }

    private static void assertValidationFailed(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action
    ) {
        assertThatThrownBy(action)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
