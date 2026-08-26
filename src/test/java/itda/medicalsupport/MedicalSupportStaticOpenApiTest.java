package itda.medicalsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class MedicalSupportStaticOpenApiTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void declaresUserAndAdminMedicalSupportPaths() throws Exception {
        String openApi = Files.readString(Path.of("docs/spec/04_M1_OpenAPI.yaml"));
        assertThat(openApi).contains("/medical-support/programs:", "/admin/medical-support/revisions/{revisionId}/verify:", "FAILED ingestion attempt recorded", "regionScope", "regionCode");
    }

    @Test
    void runtimeOpenApiExposesCanonicalRegionFields() throws Exception {
        JsonNode openApi = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(openApi.toString()).contains("regionScope", "regionCode");
    }

    @Test
    void runtimeOpenApiExposesActualIngestionResponseSchema() throws Exception {
        JsonNode openApi = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode ingestPath = openApi.path("paths")
                .path("/admin/medical-support/sources/{sourceKey}/ingestions");
        assertThat(ingestPath.isMissingNode()).isFalse();
        JsonNode responseSchema = firstSchemaWithRef(
                ingestPath.path("post").path("responses").path("200"));
        assertThat(responseSchema).isNotNull();
        JsonNode envelope = resolve(responseSchema.path("$ref").asText(), openApi);
        assertThat(envelope.path("properties").toString())
                .contains("success", "message", "data", "error");
        JsonNode data = resolve(envelope.path("properties").path("data").path("$ref").asText(), openApi);
        JsonNode properties = data.path("properties");
        assertThat(properties.path("revisionId").path("type").asText()).isEqualTo("integer");
        assertThat(properties.path("revisionId").path("format").asText()).isEqualTo("int64");
        assertThat(properties.path("created").path("type").asText()).isEqualTo("boolean");
        assertThat(properties.path("reviewStatus").path("type").asText()).isEqualTo("string");
        assertThat(properties.path("reviewStatus").path("enum").toString())
                .contains("PENDING_REVIEW", "VERIFIED", "REJECTED");
    }

    private JsonNode firstSchemaWithRef(JsonNode response) {
        for (JsonNode media : response.path("content")) {
            JsonNode schema = media.path("schema");
            if (!schema.path("$ref").asText().isEmpty()) {
                return schema;
            }
        }
        return null;
    }

    private JsonNode resolve(String reference, JsonNode openApi) {
        assertThat(reference).startsWith("#/components/schemas/");
        return openApi.path("components").path("schemas")
                .path(reference.substring("#/components/schemas/".length()));
    }
}
