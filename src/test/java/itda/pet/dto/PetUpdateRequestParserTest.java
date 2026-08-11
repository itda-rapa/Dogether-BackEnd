package itda.pet.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.PetSex;
import itda.pet.domain.PetSizeCode;
import itda.pet.dto.PetUpdateRequest.Field;
import itda.pet.service.PetUpdateCommand;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("PetUpdateRequestParser")
class PetUpdateRequestParserTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static ValidatorFactory validatorFactory;
    private static PetUpdateRequestParser parser;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        parser = new PetUpdateRequestParser(validatorFactory.getValidator());
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @Nested
    @DisplayName("Describe: JSON 구조와 필드 presence")
    class DescribeStructureAndPresence {

        @ParameterizedTest
        @MethodSource("itda.pet.dto.PetUpdateRequestParserTest#invalidRoots")
        @DisplayName("It: null과 객체가 아닌 root를 거절한다")
        void rejectsNullAndNonObjectRoot(String json) throws Exception {
            assertValidationFailed(() -> parser.parse(node(json)));
        }

        @Test
        @DisplayName("It: 빈 객체의 모든 presence는 false이고 요청은 거절한다")
        void rejectsEmptyObjectAfterDetectingNoPresence() throws Exception {
            PetUpdateRequest request = parser.parseRequest(node("{}"));

            assertThat(request.presentFields()).isEmpty();
            assertValidationFailed(() -> parser.parse(node("{}")));
        }

        @Test
        @DisplayName("It: bio explicit null을 presence true와 null 값으로 보존한다")
        void preservesExplicitNullPresence() throws Exception {
            PetUpdateCommand command = parser.parse(node("{\"bio\":null}"));

            assertThat(command.bio().present()).isTrue();
            assertThat(command.bio().value()).isNull();
            assertThat(command.nickname().present()).isFalse();
        }

        @Test
        @DisplayName("It: personalityTags 빈 배열을 빈 목록으로 보존한다")
        void preservesEmptyPersonalityTags() throws Exception {
            PetUpdateCommand command = parser.parse(
                    node("{\"personalityTags\":[]}")
            );

            assertThat(command.personalityTags().present()).isTrue();
            assertThat(command.personalityTags().value()).isEmpty();
        }

        @Test
        @DisplayName("It: personalityTags explicit null을 거절한다")
        void rejectsNullPersonalityTags() throws Exception {
            assertValidationFailed(() -> parser.parse(
                    node("{\"personalityTags\":null}")
            ));
        }

        @ParameterizedTest
        @MethodSource("itda.pet.dto.PetUpdateRequestParserTest#unknownBodies")
        @DisplayName("It: unknown field가 하나라도 있으면 요청 전체를 거절한다")
        void rejectsUnknownFields(String json) throws Exception {
            assertValidationFailed(() -> parser.parse(node(json)));
        }
    }

    @Nested
    @DisplayName("Describe: 엄격한 JSON wire 타입")
    class DescribeStrictWireTypes {

        @ParameterizedTest
        @MethodSource("itda.pet.dto.PetUpdateRequestParserTest#coercionBodies")
        @DisplayName("It: scalar coercion과 잘못된 배열 원소 타입을 거절한다")
        void rejectsCoercion(String json) throws Exception {
            assertValidationFailed(() -> parser.parse(node(json)));
        }

        @ParameterizedTest
        @MethodSource("itda.pet.dto.PetUpdateRequestParserTest#invalidEnumBodies")
        @DisplayName("It: enum의 잘못된 값과 자동 보정을 거절한다")
        void rejectsInvalidEnums(String json) throws Exception {
            assertValidationFailed(() -> parser.parse(node(json)));
        }

        @Test
        @DisplayName("It: enum과 날짜와 숫자를 정확한 타입으로 변환한다")
        void convertsStrictTypedValues() throws Exception {
            PetUpdateCommand command = parser.parse(node("""
                    {
                      "sex": "FEMALE",
                      "sizeCode": "SMALL",
                      "neutered": true,
                      "birthDate": "2020-01-02",
                      "weightKg": 3.25
                    }
                    """));

            assertThat(command.sex().value()).isEqualTo(PetSex.FEMALE);
            assertThat(command.sizeCode().value())
                    .isEqualTo(PetSizeCode.SMALL);
            assertThat(command.neutered().value()).isTrue();
            assertThat(command.birthDate().value().toString())
                    .isEqualTo("2020-01-02");
            assertThat(command.weightKg().value())
                    .isEqualByComparingTo("3.25");
        }
    }

    @Nested
    @DisplayName("Describe: 선택 필드 Bean Validation")
    class DescribeSelectedPropertyValidation {

        @Test
        @DisplayName("It: nickname을 trim한 뒤 Command에 전달한다")
        void trimsNickname() throws Exception {
            PetUpdateCommand command = parser.parse(
                    node("{\"nickname\":\"  초코  \"}")
            );

            assertThat(command.nickname().value()).isEqualTo("초코");
        }

        @ParameterizedTest
        @MethodSource("itda.pet.dto.PetUpdateRequestParserTest#invalidNicknames")
        @DisplayName("It: nickname의 NotBlank Size NoEmoji를 적용한다")
        void validatesNickname(String json) throws Exception {
            assertValidationFailed(() -> parser.parse(node(json)));
        }

        @ParameterizedTest
        @MethodSource("itda.pet.dto.PetUpdateRequestParserTest#invalidConstraints")
        @DisplayName("It: 문자열 길이와 weight 범위 및 자릿수를 검증한다")
        void validatesPropertyConstraints(String json) throws Exception {
            assertValidationFailed(() -> parser.parse(node(json)));
        }

        @Test
        @DisplayName("It: container element의 null을 Bean Validation으로 거절한다")
        void validatesContainerElementConstraint() throws Exception {
            assertValidationFailed(() -> parser.parse(
                    node("{\"personalityTags\":[\"친화적\",null]}")
            ));
        }

        @Test
        @DisplayName("It: 태그 최대 10개를 적용한다")
        void validatesTagCount() throws Exception {
            String tags = "\"태그\",".repeat(10) + "\"태그\"";

            assertValidationFailed(() -> parser.parse(
                    node("{\"personalityTags\":[" + tags + "]}")
            ));
        }

        @Test
        @DisplayName("It: missing 필드는 검증하지 않고 nullable 문자열 원문을 유지한다")
        void validatesOnlyPresentFieldsAndPreservesNullableStrings()
                throws Exception {
            PetUpdateCommand command = parser.parse(node("""
                    {
                      "breedName": "",
                      "bio": "   ",
                      "careNote": " 메모 "
                    }
                    """));

            assertThat(command.nickname().present()).isFalse();
            assertThat(command.breedName().value()).isEmpty();
            assertThat(command.bio().value()).isEqualTo("   ");
            assertThat(command.careNote().value()).isEqualTo(" 메모 ");
        }

        @Test
        @DisplayName("It: record component constraint가 validateProperty에서 적용된다")
        void recordPropertyConstraintsWorkWithValidateProperty() {
            Validator validator = validatorFactory.getValidator();
            PetUpdateRequest request = new PetUpdateRequest(
                    Set.of(Field.WEIGHT_KG),
                    null,
                    null,
                    null,
                    null,
                    null,
                    new BigDecimal("1.234"),
                    null,
                    null,
                    null,
                    null
            );

            assertThat(validator.validateProperty(request, "weightKg"))
                    .extracting(violation ->
                            violation.getPropertyPath().toString()
                    )
                    .containsOnly("weightKg");
        }
    }

    static Stream<String> invalidRoots() {
        return Stream.of("null", "[]", "\"value\"", "1", "true");
    }

    static Stream<String> unknownBodies() {
        return Stream.of(
                "{\"unknown\":1}",
                "{\"bio\":null,\"unknown\":1}",
                "{\"publicTag\":\"새태그#A7K2\"}",
                "{\"version\":1}"
        );
    }

    static Stream<String> coercionBodies() {
        return Stream.of(
                "{\"breedName\":123}",
                "{\"bio\":false}",
                "{\"careNote\":[]}",
                "{\"neutered\":\"true\"}",
                "{\"birthDate\":20260804}",
                "{\"weightKg\":\"1.25\"}",
                "{\"personalityTags\":[1]}",
                "{\"personalityTags\":[{}]}",
                "{\"personalityTags\":[[]]}",
                "{\"sex\":1}",
                "{\"sizeCode\":true}"
        );
    }

    static Stream<String> invalidEnumBodies() {
        return Stream.of(
                "{\"sex\":\"female\"}",
                "{\"sex\":\" FEMALE \"}",
                "{\"sex\":\"OTHER\"}",
                "{\"sizeCode\":\"small\"}",
                "{\"birthDate\":\"2026-02-30\"}"
        );
    }

    static Stream<String> invalidNicknames() {
        return Stream.of(
                "{\"nickname\":null}",
                "{\"nickname\":\"\"}",
                "{\"nickname\":\"   \"}",
                "{\"nickname\":\"" + "가".repeat(31) + "\"}",
                "{\"nickname\":\"몽이😀\"}"
        );
    }

    static Stream<String> invalidConstraints() {
        return Stream.of(
                "{\"breedName\":\"" + "a".repeat(101) + "\"}",
                "{\"bio\":\"" + "a".repeat(501) + "\"}",
                "{\"careNote\":\"" + "a".repeat(501) + "\"}",
                "{\"weightKg\":-0.01}",
                "{\"weightKg\":1000.00}",
                "{\"weightKg\":1.234}"
        );
    }

    private static JsonNode node(String json) throws Exception {
        return OBJECT_MAPPER.readTree(json);
    }

    private static void assertValidationFailed(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation
    ) {
        assertThatThrownBy(operation)
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).getErrorCode()
                )
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
