package itda.boardpost.dto;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class BoardPostRequestParser {

    public BoardPostCreateRequest parseCreate(JsonNode body) {
        requireCreateObjectAndFields(body);
        return new BoardPostCreateRequest(
                string(body, "title"),
                string(body, "content"),
                mediaIds(body),
                placeId(body)
        );
    }

    public BoardPostUpdateRequest parseUpdate(JsonNode body) {
        if (body == null
                || !body.isObject()
                || !Set.of("title", "content", "mediaIds", "placeId", "version")
                .containsAll(body.propertyNames())) {
            throw invalid();
        }
        if (!body.has("title") && !body.has("content") && !body.has("mediaIds") && !body.has("placeId")) {
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
                body.has("mediaIds"),
                mediaIds(body),
                body.has("placeId"),
                placeId(body),
                version.longValue()
        );
    }

    private void requireObjectAndFields(JsonNode body, Set<String> fields) {
        if (body == null || !body.isObject() || !fields.equals(body.propertyNames())) {
            throw invalid();
        }
    }

    private void requireCreateObjectAndFields(JsonNode body) {
        if (body == null
                || !body.isObject()
                || !Set.of("title", "content", "mediaIds", "placeId").containsAll(body.propertyNames())
                || !body.has("title")
                || !body.has("content")) {
            throw invalid();
        }
    }

    private List<Long> mediaIds(JsonNode body) {
        if (!body.has("mediaIds")) {
            return List.of();
        }
        JsonNode node = body.get("mediaIds");
        if (node == null || node.isNull() || !node.isArray() || node.size() > 5) {
            throw invalid();
        }
        List<Long> values = new ArrayList<>(node.size());
        Set<Long> unique = new HashSet<>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode value = node.get(index);
            if (value == null
                    || value.isNull()
                    || !value.isIntegralNumber()
                    || !value.canConvertToLong()
                    || value.longValue() <= 0) {
                throw invalid();
            }
            long mediaId = value.longValue();
            if (!unique.add(mediaId)) {
                throw invalid();
            }
            values.add(mediaId);
        }
        return List.copyOf(values);
    }

    private Integer placeId(JsonNode body) {
        if (!body.has("placeId")) {
            return null;
        }
        JsonNode node = body.get("placeId");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() <= 0) {
            throw invalid();
        }
        return node.intValue();
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
