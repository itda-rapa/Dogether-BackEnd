package itda.boardpost.dto;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class BoardPostRequestParser {

    public BoardPostCreateRequest parseCreate(JsonNode body) {
        requireObjectAndFields(body, Set.of("title", "content"));
        return new BoardPostCreateRequest(
                string(body, "title"),
                string(body, "content")
        );
    }

    public BoardPostUpdateRequest parseUpdate(JsonNode body) {
        if (body == null
                || !body.isObject()
                || !Set.of("title", "content", "version")
                .containsAll(body.propertyNames())) {
            throw invalid();
        }
        if (!body.has("title") && !body.has("content")) {
            throw invalid();
        }
        String title = body.has("title") ? string(body, "title") : null;
        String content = body.has("content") ? string(body, "content") : null;
        JsonNode version = required(body, "version");
        if (!version.isIntegralNumber()
                || !version.canConvertToLong()
                || version.longValue() < 0) {
            throw invalid();
        }
        return new BoardPostUpdateRequest(
                body.has("title"),
                title,
                body.has("content"),
                content,
                version.longValue()
        );
    }

    private void requireObjectAndFields(JsonNode body, Set<String> fields) {
        if (body == null || !body.isObject() || !fields.equals(body.propertyNames())) {
            throw invalid();
        }
    }

    private String string(JsonNode body, String name) {
        JsonNode node = required(body, name);
        if (!node.isString()) {
            throw invalid();
        }
        return node.stringValue();
    }

    private JsonNode required(JsonNode body, String name) {
        JsonNode node = body.get(name);
        if (node == null || node.isNull()) {
            throw invalid();
        }
        return node;
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
