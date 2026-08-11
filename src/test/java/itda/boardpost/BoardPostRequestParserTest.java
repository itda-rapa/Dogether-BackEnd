package itda.boardpost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.boardpost.dto.BoardPostRequestParser;
import itda.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BoardPostRequestParserTest {

    private final BoardPostRequestParser parser = new BoardPostRequestParser();
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void createAcceptsOmittedEmptyAndUpToFiveMediaIds() {
        assertThat(parse("{\"title\":\"t\",\"content\":\"c\"}").mediaIds()).isEmpty();
        assertThat(parse("{\"title\":\"t\",\"content\":\"c\",\"mediaIds\":[]}").mediaIds()).isEmpty();
        assertThat(parse("{\"title\":\"t\",\"content\":\"c\",\"mediaIds\":[5,4,3,2,1]}").mediaIds())
                .containsExactly(5L, 4L, 3L, 2L, 1L);
    }

    @Test
    void createRejectsInvalidMediaIdsAndUnknownFields() {
        for (String body : new String[] {
                "{\"title\":\"t\",\"content\":\"c\",\"mediaIds\":null}",
                "{\"title\":\"t\",\"content\":\"c\",\"mediaIds\":1}",
                "{\"title\":\"t\",\"content\":\"c\",\"mediaIds\":[null]}",
                "{\"title\":\"t\",\"content\":\"c\",\"mediaIds\":[1.5]}",
                "{\"title\":\"t\",\"content\":\"c\",\"mediaIds\":[9223372036854775808]}",
                "{\"title\":\"t\",\"content\":\"c\",\"mediaIds\":[0]}",
                "{\"title\":\"t\",\"content\":\"c\",\"mediaIds\":[-1]}",
                "{\"title\":\"t\",\"content\":\"c\",\"mediaIds\":[1,1]}",
                "{\"title\":\"t\",\"content\":\"c\",\"mediaIds\":[1,2,3,4,5,6]}",
                "{\"title\":\"t\",\"content\":\"c\",\"unexpected\":true}"
        }) {
            assertThatThrownBy(() -> parse(body)).isInstanceOf(BusinessException.class)
                    .extracting(error -> ((BusinessException) error).getErrorCode().name())
                    .isEqualTo("VALIDATION_FAILED");
        }
    }

    @Test
    void patchKeepsItsExistingStrictFieldSet() {
        assertThatThrownBy(() -> parser.parseUpdate(mapper.readTree(
                "{\"title\":\"t\",\"version\":0,\"mediaIds\":[]}")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode().name())
                .isEqualTo("VALIDATION_FAILED");
    }

    private itda.boardpost.dto.BoardPostCreateRequest parse(String json) {
        return parser.parseCreate(mapper.readTree(json));
    }
}
