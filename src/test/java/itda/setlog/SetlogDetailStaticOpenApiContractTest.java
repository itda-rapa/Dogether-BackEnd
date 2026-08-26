package itda.setlog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

@DisplayName("Setlog 상세 정적 OpenAPI 계약")
class SetlogDetailStaticOpenApiContractTest {

    private static final Path STATIC_OPEN_API =
            Path.of("docs/spec/04_M1_OpenAPI.yaml");

    @Test
    @DisplayName("공유 카드 상세 route와 응답 계약을 명시한다")
    void documentsSharedSetlogDetailRoute() throws Exception {
        Map<String, Object> openApi;
        try (InputStream input = Files.newInputStream(STATIC_OPEN_API)) {
            openApi = map(new Yaml().load(input));
        }

        Map<String, Object> paths = map(openApi.get("paths"));
        Map<String, Object> detailPath = map(paths.get("/setlogs/{setlogId}"));
        Map<String, Object> operation = map(detailPath.get("get"));
        Map<String, Object> responses = map(operation.get("responses"));
        Map<String, Object> setlogId = maps(operation.get("parameters")).stream()
                .filter(parameter -> "setlogId".equals(parameter.get("name")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> parameterSchema = map(setlogId.get("schema"));
        Map<String, Object> successResponse = map(responses.get("200"));
        Map<String, Object> cacheControl = map(map(successResponse.get("headers"))
                .get("Cache-Control"));
        Map<String, Object> cacheControlSchema = map(cacheControl.get("schema"));
        Map<String, Object> responseSchema = map(map(map(successResponse.get("content"))
                .get("application/json")).get("schema"));
        Map<String, Object> detailEnvelope = map(map(openApi.get("components"))
                .get("schemas"));
        detailEnvelope = map(detailEnvelope.get("SetlogDetailEnvelope"));
        Map<String, Object> envelopeData = map(map(detailEnvelope.get("properties"))
                .get("data"));

        assertThat(responses).containsKeys("200", "401", "403", "404");
        assertThat(setlogId)
                .containsEntry("in", "path")
                .containsEntry("required", true);
        assertThat(parameterSchema)
                .containsEntry("type", "integer")
                .containsEntry("format", "int64");
        assertThat(cacheControlSchema)
                .containsEntry("type", "string");
        assertThat(cacheControl).containsEntry("example", "no-store");
        assertThat(responseSchema)
                .containsEntry("$ref", "#/components/schemas/SetlogDetailEnvelope");
        assertThat(list(detailEnvelope.get("required")))
                .containsExactly("success", "message", "data", "error");
        assertThat(envelopeData)
                .containsEntry("$ref", "#/components/schemas/Setlog");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private List<Map<String, Object>> maps(Object value) {
        return list(value).stream()
                .map(this::map)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Object>) value;
    }
}
