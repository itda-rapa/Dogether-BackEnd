package itda.meetingverification;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
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
class MeetingVerificationOpenApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void runtimeOpenApiExposesSubmitEndpointRequestRequiredAndErrors() throws Exception {
        String path = "$.paths['/meeting-cards/{cardId}/meeting-verifications']";
        String requestSchema = "$.components.schemas.MeetingVerificationSubmitCommand";

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(path + ".post").exists())
                .andExpect(jsonPath(path + ".post.responses['200']").exists())
                .andExpect(jsonPath(path + ".post.responses['400']").exists())
                .andExpect(jsonPath(path + ".post.responses['403']").exists())
                .andExpect(jsonPath(path + ".post.responses['404']").exists())
                .andExpect(jsonPath(path + ".post.responses['409']").exists())
                .andExpect(jsonPath(requestSchema + ".required")
                        .value(hasItems("clientRequestId", "latitude", "longitude",
                                "accuracyMeters", "capturedAt")));
    }

    @Test
    void runtimeOpenApiExposesConfirmedResponseFields() throws Exception {
        String path = "$.paths['/meeting-cards/{cardId}/meeting-verifications']";
        String example = path + ".post.responses['200'].content['application/json'].example.data";

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(example + ".cardId").value(51))
                .andExpect(jsonPath(example + ".submittedPetId").value(12))
                .andExpect(jsonPath(example + ".counterpartSubmitted").value(true))
                .andExpect(jsonPath(example + ".meetingId").value(61))
                .andExpect(jsonPath(example + ".confirmed").value(true))
                .andExpect(jsonPath(example + ".verificationMethod").value("GPS"))
                .andExpect(jsonPath(example + ".confirmedAt").exists());
    }

    @Test
    void runtimeOpenApiExposesGetStatusEndpoint() throws Exception {
        String path = "$.paths['/meeting-cards/{cardId}/meeting-verification']";

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(path + ".get").exists())
                .andExpect(jsonPath(path + ".get.responses['200']").exists())
                .andExpect(jsonPath(path + ".get.responses['404']").exists())
                .andExpect(jsonPath(path + ".get.responses['403']").exists());
    }
}
