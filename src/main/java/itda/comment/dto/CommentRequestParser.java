package itda.comment.dto;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class CommentRequestParser {

    public CommentCreateRequest parseCreate(JsonNode body) {
        requireExactFields(body, Set.of("content"));
        return new CommentCreateRequest(string(body, "content"));
    }

    public CommentUpdateRequest parseUpdate(JsonNode body) {
        requireExactFields(body, Set.of("content", "version"));
        JsonNode version = required(body, "version");
        if (!version.isIntegralNumber()
                || !version.canConvertToLong()
                || version.longValue() < 0) {
            throw invalid();
        }
        return new CommentUpdateRequest(
                string(body, "content"),
                version.longValue()
        );
    }

    private void requireExactFields(JsonNode body, Set<String> fields) {
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
