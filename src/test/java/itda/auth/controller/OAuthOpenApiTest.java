package itda.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class OAuthOpenApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void runtimeOpenApiExposesOAuthExchangeAndSignupContracts() throws Exception {
        JsonNode openApi = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        JsonNode exchange = openApi.path("paths").path("/auth/oauth/exchange").path("post");
        JsonNode signup = openApi.path("paths").path("/auth/oauth/signup").path("post");
        assertThat(exchange.isMissingNode()).isFalse();
        for (String statusCode : java.util.List.of("200", "202", "400", "401", "403", "409", "410")) {
            assertThat(exchange.path("responses").has(statusCode)).as("exchange %s", statusCode).isTrue();
        }
        assertThat(exchange.path("requestBody").isMissingNode()).isFalse();
        assertThat(signup.isMissingNode()).isFalse();
        for (String statusCode : java.util.List.of("201", "400", "401", "409", "410", "422")) {
            assertThat(signup.path("responses").has(statusCode)).as("signup %s", statusCode).isTrue();
        }
        assertThat(signup.path("requestBody").isMissingNode()).isFalse();

        for (String browserPath : java.util.List.of("/oauth2/authorization/google", "/login/oauth2/code/google")) {
            JsonNode browserOperation = openApi.path("paths").path(browserPath).path("get");
            assertThat(browserOperation.isMissingNode()).as(browserPath).isFalse();
            assertThat(browserOperation.path("responses").has("302")).as(browserPath).isTrue();
            assertThat(browserOperation.path("responses").has("404")).as(browserPath).isTrue();
            assertThat(browserOperation.path("responses").path("302").path("headers")
                    .path("Location").isMissingNode()).as("%s Location header", browserPath).isFalse();
        }
    }
}
