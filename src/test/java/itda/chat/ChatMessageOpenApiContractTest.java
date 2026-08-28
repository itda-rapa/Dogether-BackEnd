package itda.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.Yaml;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Chat typed message OpenAPI 계약")
class ChatMessageOpenApiContractTest {

    private static final Path STATIC_OPEN_API = Path.of("docs/spec/04_M1_OpenAPI.yaml");

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Runtime OpenAPI가 사용자 typed payload와 멱등 응답을 노출한다")
    void runtimeOpenApiExposesTypedPayloadAndIdempotencyResponses() throws Exception {
        String operation = "$.paths['/chat/rooms/{roomId}/messages'].post";
        String properties = "$.components.schemas.ChatMessageCreateRequest.properties";

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(operation + ".responses['200']").exists())
                .andExpect(jsonPath(operation + ".responses['201']").exists())
                .andExpect(jsonPath(operation + ".responses['400']").exists())
                .andExpect(jsonPath(operation + ".responses['403']").exists())
                .andExpect(jsonPath(operation + ".responses['404']").exists())
                .andExpect(jsonPath(operation + ".responses['409']").exists())
                .andExpect(jsonPath(properties + ".clientMessageId.maxLength").value(64))
                .andExpect(jsonPath(properties + ".type.enum")
                        .value(containsInAnyOrder("TEXT", "IMAGE", "VIDEO", "SETLOG_SHARE")))
                .andExpect(jsonPath(properties + ".body.maxLength").value(2000))
                .andExpect(jsonPath(properties + ".mediaId").exists())
                .andExpect(jsonPath(properties + ".setlogId").exists())
                .andExpect(jsonPath("$.components.schemas.ChatMessageResponse.properties.attachment").exists())
                .andExpect(jsonPath("$.components.schemas.ChatMessageResponse.properties.sharedSetlog").exists());
    }

    @Test
    @DisplayName("정적 OpenAPI가 Runtime과 같은 typed request·응답 필드를 명시한다")
    void staticOpenApiDocumentsTypedPayloadAndResponse() throws Exception {
        Map<String, Object> openApi;
        try (InputStream input = Files.newInputStream(STATIC_OPEN_API)) {
            openApi = map(new Yaml().load(input));
        }

        Map<String, Object> post = map(map(openApi.get("paths"))
                .get("/chat/rooms/{roomId}/messages"));
        post = map(post.get("post"));
        Map<String, Object> responses = map(post.get("responses"));
        Map<String, Object> schemas = map(map(openApi.get("components")).get("schemas"));
        Map<String, Object> request = map(schemas.get("ChatMessageCreateRequest"));
        Map<String, Object> requestProperties = map(request.get("properties"));
        Map<String, Object> type = map(requestProperties.get("type"));
        Map<String, Object> message = map(schemas.get("ChatMessage"));
        Map<String, Object> messageProperties = map(message.get("properties"));

        assertThat(responses).containsKeys("200", "201", "400", "403", "404", "409");
        assertThat(list(type.get("enum")))
                .containsExactly("TEXT", "IMAGE", "VIDEO", "SETLOG_SHARE");
        assertThat(requestProperties).containsKeys("clientMessageId", "body", "mediaId", "setlogId");
        assertThat(messageProperties).containsKeys("attachment", "sharedSetlog", "clientMessageId");
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
