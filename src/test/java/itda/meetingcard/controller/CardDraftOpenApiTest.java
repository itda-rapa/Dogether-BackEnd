package itda.meetingcard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Runtime OpenAPI({@code /v3/api-docs})가 실제 {@code CardDraftFallbackReason} enum 과
 * 동기화돼 있는지 검증한다. 422 매핑 도입으로 {@code INVALID_REQUEST} 가 enum 에 추가됐고,
 * 이 값은 CardDraft REST 응답에도 노출될 수 있다.
 *
 * <p>수동 CardDraft 엔드포인트({@code POST /chat/rooms/{roomId}/card-drafts})의 응답은
 * {@code CardDraftSwaggerSupporter} 의 정적 example 로 대체되어 runtime 스키마가 생성되지
 * 않으므로, 같은 enum 을 공유하는 {@code OpenChatCardDraftResponse.fallbackReason} 런타임
 * 스키마에서 enum 동기화를 검증한다. 정적 M1 OpenAPI({@code docs/spec/04_M1_OpenAPI.yaml})
 * 의 {@code CardDraft.fallbackReason} enum 은 별도로 문서와 동기화돼 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CardDraftOpenApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Runtime OpenAPI 의 fallbackReason enum 에 INVALID_REQUEST 가 있다")
    void runtimeOpenApiExposesInvalidRequestInCardDraftFallbackReason() throws Exception {
        JsonNode openApi = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        JsonNode fallbackReason = openApi.path("components").path("schemas")
                .path("OpenChatCardDraftResponse").path("properties").path("fallbackReason");
        assertThat(fallbackReason.isMissingNode())
                .as("runtime OpenAPI OpenChatCardDraftResponse.fallbackReason 스키마가 존재해야 한다")
                .isFalse();

        List<String> enumValues = new ArrayList<>();
        fallbackReason.path("enum").forEach(node -> enumValues.add(node.textValue()));

        assertThat(enumValues).contains(
                "TIMEOUT", "MODEL_ERROR", "INSUFFICIENT_CONTEXT", "INVALID_REQUEST");
    }
}
