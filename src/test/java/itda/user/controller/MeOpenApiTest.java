package itda.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class MeOpenApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void runtimeOpenApiExposesOnlyOptionalPublicPatchFields() throws Exception {
        JsonNode openApi = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode operation = openApi.path("paths").path("/me").path("patch");
        JsonNode requestSchema = resolve(openApi, operation.path("requestBody")
                .path("content").path("application/json").path("schema"));

        assertThat(operation.path("responses").has("200")).isTrue();
        assertThat(operation.path("responses").has("400")).isTrue();
        assertThat(operation.path("responses").has("401")).isTrue();
        assertThat(operation.path("responses").has("403")).isFalse();
        assertThat(operation.path("responses").has("409")).isTrue();
        assertThat(operation.path("responses").has("422")).isTrue();
        assertThat(fieldNames(requestSchema.path("properties")))
                .containsExactlyInAnyOrder("nickname", "neighborhoodCode", "weightKg");
        assertThat(requiredProperties(requestSchema)).isEmpty();
        assertThat(requestSchema.path("properties").path("weightKg")
                .path("description").asText()).contains("사용자 체중");
        JsonNode weightKg = requestSchema.path("properties").path("weightKg");
        assertExactNullableNumber(weightKg, "PATCH /me request");
        assertThat(openApi.path("components").path("schemas").has("JsonNode")).isFalse();
        assertThat(operation.toString()).doesNotContain("JsonNode", "MeUpdateCommand");
    }

    @Test
    void runtimeOpenApiRetainsMeResponseFieldsAndExposesNullableNumericWeight() throws Exception {
        JsonNode openApi = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode operation = openApi.path("paths").path("/me").path("get");
        JsonNode envelope = resolveSchema(openApi, responseSchema(operation, "200"));
        JsonNode response = resolveSchema(
                openApi, envelope.path("properties").path("data"));

        assertThat(fieldNames(response.path("properties"))).containsExactlyInAnyOrder(
                "userId", "email", "nickname", "publicTag", "role", "accountStatus",
                "accessLevel", "neighborhoodCode", "activePetId", "weightKg"
        );
        assertExactNullableNumber(response.path("properties").path("weightKg"), "GET /me response");
    }

    private JsonNode resolve(JsonNode openApi, JsonNode reference) {
        String value = reference.path("$ref").asText();
        assertThat(value).isEqualTo("#/components/schemas/MeUpdateRequest");
        return openApi.path("components").path("schemas").path("MeUpdateRequest");
    }

    private JsonNode responseSchema(JsonNode operation, String statusCode) {
        JsonNode response = operation.path("responses").path(statusCode);
        assertThat(response.isObject()).isTrue();
        JsonNode content = response.path("content");
        JsonNode media = content.has("application/json")
                ? content.path("application/json") : content.path("*/*");
        assertThat(media.isObject()).isTrue();
        return media.path("schema");
    }

    private JsonNode resolveSchema(JsonNode openApi, JsonNode schemaReference) {
        String reference = schemaReference.path("$ref").asText();
        assertThat(reference).startsWith("#/components/schemas/");
        String name = reference.substring("#/components/schemas/".length());
        JsonNode schema = openApi.path("components").path("schemas").path(name);
        assertThat(schema.isObject()).isTrue();
        return schema;
    }

    private Set<String> fieldNames(JsonNode fields) {
        java.util.Set<String> names = new java.util.HashSet<>();
        names.addAll(fields.propertyNames());
        return names;
    }

    private Set<String> requiredProperties(JsonNode schema) {
        Set<String> required = new java.util.HashSet<>();
        for (JsonNode property : schema.path("required")) {
            required.add(property.asText());
        }
        return required;
    }

    private void assertExactNullableNumber(JsonNode property, String description) {
        assertThat(property.isObject()).as("%s weightKg property", description).isTrue();
        assertThat(property.path("type").isArray()).as("%s weightKg type", description).isTrue();
        List<String> types = java.util.stream.StreamSupport.stream(
                        property.path("type").spliterator(), false)
                .map(JsonNode::asText)
                .toList();
        assertThat(types).as("%s weightKg exact type", description)
                .containsExactly("number", "null");
    }
}
