package itda.pet.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.LinkedHashSet;
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
class PetPublicProfileOpenApiTest {

    private static final List<String> PUBLIC_FIELDS = List.of(
            "petId", "publicTag", "nickname", "profileUrl", "verified", "breedName", "sex",
            "neutered", "birthDate", "sizeCode", "bio", "personalityTags", "helpfulReceivedCount",
            "relationship"
    );
    private static final List<String> FORBIDDEN_FIELDS = List.of(
            "ownerUserId", "ownerPublicTag", "weightKg", "careNote", "status", "deletedAt",
            "verifiedAt", "active", "version"
    );

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void runtimeOpenApiExposesPublicProfilePathSchemaAndNotFoundContract() throws Exception {
        JsonNode openApi = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode operation = openApi.path("paths").path("/pets/{petId}/profile").path("get");
        JsonNode responseSchema = operation.path("responses").path("200").path("content")
                .path("application/json").path("schema");
        JsonNode envelope = openApi.path("components").path("schemas")
                .path("ApiResponsePetPublicProfileResponse");
        JsonNode schema = openApi.path("components").path("schemas").path("PetPublicProfileResponse");

        assertThat(operation.isMissingNode()).isFalse();
        assertThat(operation.path("responses").has("200")).isTrue();
        assertThat(operation.path("responses").has("401")).isTrue();
        assertThat(operation.path("responses").has("404")).isTrue();
        assertThat(responseSchema.path("$ref").asString())
                .isEqualTo("#/components/schemas/ApiResponsePetPublicProfileResponse");
        assertThat(envelope.path("properties").path("data").path("$ref").asString())
                .isEqualTo("#/components/schemas/PetPublicProfileResponse");
        assertThat(fieldNames(schema.path("properties")))
                .containsExactlyInAnyOrderElementsOf(PUBLIC_FIELDS);
        assertThat(arrayValues(schema.path("required")))
                .containsExactlyInAnyOrderElementsOf(PUBLIC_FIELDS);
        for (String field : PUBLIC_FIELDS) {
            assertThat(schema.path("properties").path(field).isMissingNode())
                    .as("public property %s", field).isFalse();
        }
        for (String field : FORBIDDEN_FIELDS) {
            assertThat(schema.path("properties").path(field).isMissingNode())
                    .as("forbidden property %s", field).isTrue();
        }

        for (String nullableField : List.of(
                "profileUrl", "breedName", "sex", "neutered", "birthDate", "sizeCode", "bio",
                "relationship"
        )) {
            assertRequiredNullable(schema.path("properties").path(nullableField), nullableField);
        }
        JsonNode relationship = schema.path("properties").path("relationship");
        assertThat(relationship.path("enum").toString())
                .contains("NONE", "REQUEST_SENT", "REQUEST_RECEIVED", "FRIEND");
    }

    private void assertRequiredNullable(JsonNode property, String name) {
        boolean nullableLegacy = property.path("nullable").asBoolean(false);
        boolean nullableOpenApi31 = property.path("type").isArray()
                && java.util.stream.StreamSupport.stream(
                        property.path("type").spliterator(), false
                ).anyMatch(type -> "null".equals(type.asString()));
        assertThat(nullableLegacy || nullableOpenApi31)
                .as("%s must explicitly expose nullability", name).isTrue();
    }

    private Set<String> fieldNames(JsonNode object) {
        return new LinkedHashSet<>(object.propertyNames());
    }

    private Set<String> arrayValues(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }
}
