package itda.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.comment.dto.CommentRequestParser;
import itda.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CommentRequestParserTest {

    private final CommentRequestParser parser = new CommentRequestParser();
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void createAcceptsOnlyAStringContentFieldWithoutTransformingIt() {
        assertThat(parser.parseCreate(json("{\"content\":\"  원문  \"}")).content())
                .isEqualTo("  원문  ");
    }

    @Test
    void createRejectsMissingNullWrongTypeAndServerManagedOrUnknownFields() {
        for (String body : new String[] {
                "null", "[]", "{}", "{\"content\":null}", "{\"content\":1}",
                "{\"content\":\"text\",\"authorUserId\":1}",
                "{\"content\":\"text\",\"authorPetId\":1}",
                "{\"content\":\"text\",\"postId\":1}",
                "{\"content\":\"text\",\"version\":0}",
                "{\"content\":\"text\",\"extra\":true}"
        }) {
            assertInvalid(() -> parser.parseCreate(json(body)));
        }
    }

    @Test
    void updateRequiresExactlyContentAndNonNegativeIntegralVersion() {
        var request = parser.parseUpdate(json("{\"content\":\"수정\",\"version\":0}"));
        assertThat(request.content()).isEqualTo("수정");
        assertThat(request.version()).isZero();

        for (String body : new String[] {
                "null", "[]", "{}", "{\"content\":\"text\"}",
                "{\"version\":0}", "{\"content\":null,\"version\":0}",
                "{\"content\":\"text\",\"version\":null}",
                "{\"content\":\"text\",\"version\":1.5}",
                "{\"content\":\"text\",\"version\":-1}",
                "{\"content\":\"text\",\"version\":9223372036854775808}",
                "{\"content\":\"text\",\"version\":0,\"extra\":true}"
        }) {
            assertInvalid(() -> parser.parseUpdate(json(body)));
        }
    }

    private void assertInvalid(ThrowingAction action) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode().name())
                .isEqualTo("VALIDATION_FAILED");
    }

    private tools.jackson.databind.JsonNode json(String value) {
        return mapper.readTree(value);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
