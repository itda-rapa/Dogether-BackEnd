package itda.setlog;

import static org.hamcrest.Matchers.containsInAnyOrder;
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
class SetlogUploadOpenApiTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void runtimeOpenApiExposesUploadRequestBoundsAndResponses() throws Exception {
        String operation = "$.paths['/setlogs/uploads'].post";
        String schema = "$.components.schemas.SetlogUploadCreateRequest.properties";

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(operation + ".responses['201']").exists())
                .andExpect(jsonPath(operation + ".responses['400']").exists())
                .andExpect(jsonPath(operation + ".responses['403']").exists())
                .andExpect(jsonPath(operation + ".responses['413']").exists())
                .andExpect(jsonPath(operation + ".responses['415']").exists())
                .andExpect(jsonPath(operation + ".responses['502']").exists())
                .andExpect(jsonPath(operation + ".responses['503']").exists())
                .andExpect(jsonPath(schema + ".fileName.maxLength").value(255))
                .andExpect(jsonPath(schema + ".size.minimum").value(1))
                .andExpect(jsonPath(schema + ".size.maximum").value(209715200))
                .andExpect(jsonPath(schema + ".contentType.enum")
                        .value(containsInAnyOrder("video/mp4", "video/webm")));
    }

    @Test
    void runtimeOpenApiExposesUploadCompletionIdempotencyAndFailureResponses() throws Exception {
        String operation = "$.paths['/setlogs/uploads/{uploadId}/complete'].post";

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(operation + ".responses['200']").exists())
                .andExpect(jsonPath(operation + ".responses['201']").exists())
                .andExpect(jsonPath(operation + ".responses['400']").exists())
                .andExpect(jsonPath(operation + ".responses['403']").exists())
                .andExpect(jsonPath(operation + ".responses['404']").exists())
                .andExpect(jsonPath(operation + ".responses['409']").exists())
                .andExpect(jsonPath(operation + ".responses['502']").exists())
                .andExpect(jsonPath(operation + ".responses['503']").exists())
                .andExpect(jsonPath(operation
                        + ".requestBody.content['application/json'].schema.$ref")
                        .value("#/components/schemas/SetlogUploadCompleteRequest"))
                .andExpect(jsonPath("$.components.schemas.SetlogUploadCompleteRequest"
                        + ".properties.clientRequestId.format").value("uuid"));
    }
}
