package itda.pet.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("IfMatchVersionParser")
class IfMatchVersionParserTest {

    private final IfMatchVersionParser parser = new IfMatchVersionParser();

    @Test
    @DisplayName("quoted non-negative long과 HTTP OWS만 허용한다")
    void parsesQuotedVersionWithOuterOws() {
        assertThat(parser.parse(List.of("\"0\""))).isZero();
        assertThat(parser.parse(List.of("\"3\""))).isEqualTo(3L);
        assertThat(parser.parse(List.of(" \t\"42\"\t "))).isEqualTo(42L);
        assertThat(parser.parse(List.of("\"9223372036854775807\"")))
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("누락, 반복, 약한 ETag 및 숫자 형식 밖의 모든 값을 거절한다")
    void rejectsMalformedOrRepeatedHeaderValues() {
        List<List<String>> invalidValues = List.of(
                List.of(),
                List.of(" "),
                List.of("3"),
                List.of("W/\"3\""),
                List.of("*"),
                List.of("\"3\"", "\"3\""),
                List.of("\"3\", \"4\""),
                List.of("\"-1\""),
                List.of("\"+1\""),
                List.of("\"three\""),
                List.of("\"3 4\""),
                List.of("\"9223372036854775808\""),
                List.of("\"3"),
                List.of("3\"")
        );

        for (List<String> invalidValue : invalidValues) {
            assertThatThrownBy(() -> parser.parse(invalidValue))
                    .isInstanceOf(BusinessException.class)
                    .extracting(error -> ((BusinessException) error).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
        assertThatThrownBy(() -> parser.parse(null))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
