package itda.boardpost;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class BoardPostOpenApiTest {

    @Autowired private MockMvc mockMvc;

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
}
