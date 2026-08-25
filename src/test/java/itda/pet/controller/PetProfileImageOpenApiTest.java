package itda.pet.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class PetProfileImageOpenApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void runtimeOpenApiExposesStrongQuotedIfMatchAndPetResponseContract() throws Exception {
        String create = "$.paths['/pets'].post";
        String list = "$.paths['/pets/me'].get";
        String detail = "$.paths['/pets/{petId}'].get";
        String patch = "$.paths['/pets/{petId}'].patch";
        String put = "$.paths['/pets/{petId}/profile-image'].put";
        String post = "$.paths['/pets/{petId}/profile-image'].post";
        String delete = "$.paths['/pets/{petId}/profile-image'].delete";
        JsonNode openApi = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(ifMatchExample(openApi, "put")).isEqualTo("\"3\"");
        assertThat(ifMatchExample(openApi, "delete")).isEqualTo("\"3\"");

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(put).exists())
                .andExpect(jsonPath(create + ".responses['201'].content['application/json'].example.data.pet.version")
                        .value(0))
                .andExpect(jsonPath(list + ".responses['200'].content['application/json'].example.data[0].version")
                        .value(0))
                .andExpect(jsonPath(detail + ".responses['200'].content['application/json'].example.data.version")
                        .value(0))
                .andExpect(jsonPath(patch + ".responses['200'].content['application/json'].example.data.version")
                        .value(1))
                .andExpect(jsonPath(post + ".responses['201']").exists())
                .andExpect(jsonPath(post + ".responses['201'].content['application/json'].example.data.version")
                        .value(1))
                .andExpect(jsonPath(delete).exists())
                .andExpect(jsonPath(put + ".requestBody.required").value(true))
                .andExpect(jsonPath(put + ".requestBody.content['application/json'].schema.$ref")
                        .value("#/components/schemas/PetProfileImageRequest"))
                .andExpect(jsonPath(put + ".responses['200']").exists())
                .andExpect(jsonPath(put + ".responses['400']").exists())
                .andExpect(jsonPath(put + ".responses['403']").exists())
                .andExpect(jsonPath(put + ".responses['404']").exists())
                .andExpect(jsonPath(put + ".responses['409']").exists())
                .andExpect(jsonPath(put + ".responses['422']").exists())
                .andExpect(jsonPath(put + ".responses['200'].content['application/json'].example.data.version")
                        .value(4))
                .andExpect(jsonPath(delete + ".requestBody").doesNotExist())
                .andExpect(jsonPath(delete + ".responses['204']").exists())
                .andExpect(jsonPath(delete + ".responses['204'].content").doesNotExist())
                .andExpect(jsonPath(delete + ".responses['400']").exists())
                .andExpect(jsonPath(delete + ".responses['403']").exists())
                .andExpect(jsonPath(delete + ".responses['404']").exists())
                .andExpect(jsonPath(delete + ".responses['409']").exists())
                .andExpect(jsonPath("$.components.schemas.PetResponse.properties.version.type")
                        .value("integer"))
                .andExpect(jsonPath("$.components.schemas.PetResponse.properties.version.format")
                        .value("int64"));
    }

    private String ifMatchExample(JsonNode openApi, String operation) {
        JsonNode parameters = openApi.path("paths")
                .path("/pets/{petId}/profile-image")
                .path(operation)
                .path("parameters");
        for (JsonNode parameter : parameters) {
            if ("If-Match".equals(parameter.path("name").asString())) {
                assertThat(parameter.path("required").asBoolean()).isTrue();
                return parameter.path("example").textValue();
            }
        }
        throw new AssertionError("If-Match parameter is missing");
    }
}
