package itda.boardpost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.boardpost.dto.BoardPostRequestParser;
import itda.boardpost.dto.BoardPostUpdateRequest;
import itda.common.exception.BusinessException;
import java.util.List;
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
    void patchDistinguishesOmittedEmptyAndOrderedMediaIds() {
        BoardPostUpdateRequest omitted = parseUpdate("{\"version\":0,\"title\":\"t\"}");
        assertThat(omitted.titlePresent()).isTrue();
        assertThat(omitted.title()).isEqualTo("t");
        assertThat(omitted.contentPresent()).isFalse();
        assertThat(omitted.mediaIdsPresent()).isFalse();
        assertThat(omitted.mediaIds()).isEmpty();

        BoardPostUpdateRequest empty = parseUpdate("{\"version\":1,\"mediaIds\":[]}");
        assertThat(empty.titlePresent()).isFalse();
        assertThat(empty.contentPresent()).isFalse();
        assertThat(empty.mediaIdsPresent()).isTrue();
        assertThat(empty.mediaIds()).isEmpty();

        BoardPostUpdateRequest ordered = parseUpdate(
                "{\"version\":2,\"mediaIds\":[3,8,5]}"
        );
        assertThat(ordered.mediaIdsPresent()).isTrue();
        assertThat(ordered.mediaIds()).containsExactly(3L, 8L, 5L);
    }

    @Test
    void patchAcceptsEveryTitleContentAndMediaPresenceCombination() {
        for (String body : List.of(
                "{\"version\":0,\"title\":\"title\"}",
                "{\"version\":0,\"content\":\"content\"}",
                "{\"version\":0,\"mediaIds\":[1]}",
                "{\"version\":0,\"title\":\"title\",\"content\":\"content\"}",
                "{\"version\":0,\"title\":\"title\",\"mediaIds\":[1]}",
                "{\"version\":0,\"content\":\"content\",\"mediaIds\":[1]}",
                "{\"version\":0,\"title\":\"title\",\"content\":\"content\",\"mediaIds\":[1]}"
        )) {
            assertThat(parseUpdate(body).version()).isZero();
        }
    }

    @Test
    void patchAcceptsExactlyFiveMediaIds() {
        assertThat(parseUpdate("{\"version\":0,\"mediaIds\":[5,4,3,2,1]}").mediaIds())
                .containsExactly(5L, 4L, 3L, 2L, 1L);
    }

    @Test
    void patchRejectsMissingOrInvalidVersionAndInvalidMediaIds() {
        for (String body : List.of(
                "{\"title\":\"t\"}",
                "{\"title\":\"t\",\"version\":null}",
                "{\"title\":\"t\",\"version\":-1}",
                "{\"title\":\"t\",\"version\":1.5}",
                "{\"title\":\"t\",\"version\":9223372036854775808}",
                "{\"version\":0,\"mediaIds\":null}",
                "{\"version\":0,\"mediaIds\":1}",
                "{\"version\":0,\"mediaIds\":[null]}",
                "{\"version\":0,\"mediaIds\":[0]}",
                "{\"version\":0,\"mediaIds\":[-1]}",
                "{\"version\":0,\"mediaIds\":[1.5]}",
                "{\"version\":0,\"mediaIds\":[9223372036854775808]}",
                "{\"version\":0,\"mediaIds\":[1,1]}",
                "{\"version\":0,\"mediaIds\":[1,2,3,4,5,6]}"
        )) {
            assertInvalidPatch(body);
        }
    }

    @Test
    void patchRejectsEmptyUnknownAndNonStringTextFields() {
        for (String body : List.of(
                "{\"version\":0}",
                "{\"version\":0,\"title\":\"t\",\"unexpected\":true}",
                "{\"version\":0,\"title\":null}",
                "{\"version\":0,\"title\":1}",
                "{\"version\":0,\"content\":null}",
                "{\"version\":0,\"content\":1}"
        )) {
            assertInvalidPatch(body);
        }
    }

    private itda.boardpost.dto.BoardPostCreateRequest parse(String json) {
        return parser.parseCreate(mapper.readTree(json));
    }

    private BoardPostUpdateRequest parseUpdate(String json) {
        return parser.parseUpdate(mapper.readTree(json));
    }

    private void assertInvalidPatch(String body) {
        assertThatThrownBy(() -> parseUpdate(body)).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode().name())
                .isEqualTo("VALIDATION_FAILED");
    }
}
