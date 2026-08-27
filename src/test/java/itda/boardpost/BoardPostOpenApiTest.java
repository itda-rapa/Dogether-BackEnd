package itda.boardpost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
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
class BoardPostOpenApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void runtimeOpenApiExposesReactionPutDeleteTypeEnumAndMutationResponse() throws Exception {
        String path = "$.paths['/posts/{postId}/reactions/{type}']";

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(path + ".put").exists())
                .andExpect(jsonPath(path + ".delete").exists())
                .andExpect(jsonPath(path + ".put.requestBody").doesNotExist())
                .andExpect(jsonPath(path + ".delete.requestBody").doesNotExist())
                .andExpect(jsonPath(path + ".put.responses['200']").exists())
                .andExpect(jsonPath(path + ".delete.responses['200']").exists())
                .andExpect(jsonPath(path + ".put.responses['400']").exists())
                .andExpect(jsonPath(path + ".delete.responses['400']").exists())
                .andExpect(jsonPath(path + ".put.parameters[*].name")
                        .value(org.hamcrest.Matchers.hasItem("type")))
                .andExpect(jsonPath(path + ".put.parameters[?(@.name == 'type')].schema.enum[0]")
                        .value(org.hamcrest.Matchers.hasItem("LIKE")))
                .andExpect(jsonPath(path + ".put.parameters[?(@.name == 'type')].schema.enum[1]")
                        .value(org.hamcrest.Matchers.hasItem("HELPFUL")))
                .andExpect(jsonPath(path + ".delete.parameters[*].name")
                        .value(org.hamcrest.Matchers.hasItem("type")))
                .andExpect(jsonPath(path + ".delete.parameters[?(@.name == 'type')].schema.enum[0]")
                        .value(org.hamcrest.Matchers.hasItem("LIKE")))
                .andExpect(jsonPath(path + ".delete.parameters[?(@.name == 'type')].schema.enum[1]")
                        .value(org.hamcrest.Matchers.hasItem("HELPFUL")));
    }

    @Test
    void runtimeOpenApiExposesBoardPatchMediaReplacementAndVersionContract() throws Exception {
        String operation = "$.paths['/posts/{postId}'].patch";
        String schema = "$.components.schemas.BoardPostPatchRequest";

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(operation + ".requestBody").exists())
                .andExpect(jsonPath(operation + ".responses['200']").exists())
                .andExpect(jsonPath(operation + ".responses['400']").exists())
                .andExpect(jsonPath(operation + ".responses['409']").exists())
                .andExpect(jsonPath(schema + ".required")
                        .value(org.hamcrest.Matchers.hasItem("version")))
                .andExpect(jsonPath(schema + ".properties.version.type").value("integer"))
                .andExpect(jsonPath(schema + ".properties.version.minimum").value(0))
                .andExpect(jsonPath(schema + ".properties.mediaIds.type").value("array"))
                .andExpect(jsonPath(schema + ".properties.mediaIds.maxItems").value(5))
                .andExpect(jsonPath(schema + ".properties.mediaIds.uniqueItems").value(true))
                .andExpect(jsonPath(schema + ".properties.mediaIds.items.minimum").value(1))
                .andExpect(jsonPath(operation + ".requestBody.content['application/json'].example.mediaIds[0]")
                        .value(10))
                .andExpect(jsonPath(operation + ".responses['200'].content['application/json'].example.data.images[0].mediaId")
                        .value(10))
                .andExpect(jsonPath(operation + ".responses['200'].content['application/json'].example.data.images[1].mediaId")
                        .value(11));
    }

    @Test
    void runtimeOpenApiExposesOptionalNullablePositiveIntegerPlaceIdContract() throws Exception {
        JsonNode openApi = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode schemas = openApi.path("components").path("schemas");

        assertPlaceIdProperty(schemas.path("BoardPostCreateRequest"), "create request", true);
        assertPlaceIdProperty(schemas.path("BoardPostPatchRequest"), "patch request", true);
        assertPlaceIdProperty(schemas.path("BoardPostResponse"), "response", false);
    }

    private void assertPlaceIdProperty(JsonNode schema, String description, boolean optional) {
        assertThat(schema.isMissingNode()).as("%s schema exists", description).isFalse();
        JsonNode placeId = property(schema, "placeId");
        assertThat(placeId.isMissingNode()).as("%s placeId property exists", description).isFalse();

        boolean integer = placeId.path("type").isTextual()
                ? "integer".equals(placeId.path("type").asText())
                : containsType(placeId.path("type"), "integer");
        assertThat(integer).as("%s placeId is integer: %s", description, placeId).isTrue();
        assertThat(placeId.path("minimum").isNumber())
                .as("%s placeId minimum is numeric", description).isTrue();
        assertThat(placeId.path("minimum").asInt())
                .as("%s placeId minimum", description).isEqualTo(1);
        if (placeId.has("format")) {
            assertThat(placeId.path("format").asText())
                    .as("%s placeId format", description).isEqualTo("int32");
        }

        boolean nullableLegacy = placeId.path("nullable").asBoolean(false);
        boolean nullableOpenApi31 = containsType(placeId.path("type"), "null");
        assertThat(nullableLegacy || nullableOpenApi31)
                .as("%s placeId explicitly exposes nullability", description).isTrue();
        if (optional) {
            assertThat(requiredProperties(schema)).as("%s required properties", description)
                    .doesNotContain("placeId");
        }
    }

    private JsonNode property(JsonNode schema, String name) {
        if (schema.path("properties").has(name)) {
            return schema.path("properties").path(name);
        }
        for (JsonNode part : schema.path("allOf")) {
            if (part.path("properties").has(name)) {
                return part.path("properties").path(name);
            }
        }
        return schema.path("properties").path(name);
    }

    private boolean containsType(JsonNode types, String expected) {
        if (!types.isArray()) {
            return false;
        }
        for (JsonNode type : types) {
            if (expected.equals(type.asText())) {
                return true;
            }
        }
        return false;
    }

    private Set<String> requiredProperties(JsonNode schema) {
        Set<String> required = new HashSet<>();
        for (JsonNode property : schema.path("required")) {
            required.add(property.asText());
        }
        for (JsonNode part : schema.path("allOf")) {
            for (JsonNode property : part.path("required")) {
                required.add(property.asText());
            }
        }
        return required;
    }
}
