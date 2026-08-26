package itda.medicalsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

@DisplayName("Medical support 수집 정적 OpenAPI 계약")
class MedicalSupportIngestionStaticOpenApiContractTest {

    private static final Path STATIC_OPEN_API =
            Path.of("docs/spec/04_M1_OpenAPI.yaml");

    @Test
    @DisplayName("Admin ingestion 성공 응답이 실제 MedicalSupportIngestionResponse 계약을 schema로 명시한다")
    void documentsActualIngestionResponseSchema() throws Exception {
        Map<String, Object> openApi;
        try (InputStream input = Files.newInputStream(STATIC_OPEN_API)) {
            openApi = map(new Yaml().load(input));
        }

        Map<String, Object> paths = map(openApi.get("paths"));
        Map<String, Object> ingestPath = map(paths.get("/admin/medical-support/sources/{sourceKey}/ingestions"));
        Map<String, Object> operation = map(ingestPath.get("post"));
        Map<String, Object> successResponse = map(map(operation.get("responses")).get("200"));
        Map<String, Object> responseSchema = map(map(map(successResponse.get("content"))
                .get("application/json")).get("schema"));

        Map<String, Object> schemas = map(map(openApi.get("components")).get("schemas"));
        Map<String, Object> envelope = map(schemas.get("MedicalSupportIngestionEnvelope"));
        Map<String, Object> envelopeData = map(map(envelope.get("properties")).get("data"));
        Map<String, Object> ingestion = map(schemas.get("MedicalSupportIngestion"));
        Map<String, Object> revisionId = map(map(ingestion.get("properties")).get("revisionId"));
        Map<String, Object> created = map(map(ingestion.get("properties")).get("created"));
        Map<String, Object> reviewStatus = map(map(ingestion.get("properties")).get("reviewStatus"));

        assertThat(responseSchema)
                .containsEntry("$ref", "#/components/schemas/MedicalSupportIngestionEnvelope");
        assertThat(list(envelope.get("required")))
                .containsExactly("success", "message", "data", "error");
        assertThat(envelopeData)
                .containsEntry("$ref", "#/components/schemas/MedicalSupportIngestion");
        assertThat(list(ingestion.get("required")))
                .containsExactly("revisionId", "created", "reviewStatus");
        assertThat(revisionId)
                .containsEntry("type", "integer")
                .containsEntry("format", "int64");
        assertThat(created).containsEntry("type", "boolean");
        assertThat(reviewStatus)
                .containsEntry("type", "string");
        assertThat(list(reviewStatus.get("enum")))
                .containsExactly("PENDING_REVIEW", "VERIFIED", "REJECTED");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Object>) value;
    }
}
