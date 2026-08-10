package itda.board.dto;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class BoardUpdateRequestParser {

    private static final Set<String> ALLOWED_FIELDS = Set.of("name", "version");

    public BoardUpdateRequest parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw validationFailed();
        }

        if (!ALLOWED_FIELDS.containsAll(body.propertyNames())) {
            throw validationFailed();
        }

        JsonNode name = required(body, "name");
        if (!name.isString()) {
            throw validationFailed();
        }

        JsonNode version = required(body, "version");
        if (!version.isIntegralNumber()
                || !version.canConvertToLong()
                || version.longValue() < 0) {
            throw validationFailed();
        }

        return new BoardUpdateRequest(name.stringValue(), version.longValue());
    }

    private JsonNode required(JsonNode body, String fieldName) {
        if (!body.has(fieldName)) {
            throw validationFailed();
        }
        JsonNode value = body.get(fieldName);
        if (value == null || value.isNull()) {
            throw validationFailed();
        }
        return value;
    }

    private BusinessException validationFailed() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
