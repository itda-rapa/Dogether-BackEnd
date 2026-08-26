package itda.meetingverification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.common.constants.ErrorCode;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.Yaml;

/**
 * Confirmation Code fallback의 정적 OpenAPI 정본과 런타임 /v3/api-docs 동기화를 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MeetingConfirmationCodeOpenApiTest {

    private static final Path STATIC_OPEN_API = Path.of("docs/spec/04_M1_OpenAPI.yaml");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void staticOpenApiDeclaresConfirmationCodePaths() throws Exception {
        Map<String, Object> openApi;
        try (InputStream input = Files.newInputStream(STATIC_OPEN_API)) {
            openApi = map(new Yaml().load(input));
        }
        Map<String, Object> paths = map(openApi.get("paths"));
        Map<String, Object> schemas = map(map(openApi.get("components")).get("schemas"));

        assertThat(paths).containsKeys(
                "/meeting-cards/{cardId}/confirmation-codes",
                "/meeting-cards/{cardId}/confirmation-codes/verify",
                "/meeting-cards/{cardId}/confirmation-codes/confirm");

        Map<String, Object> issueOperation = map(map(paths.get(
                "/meeting-cards/{cardId}/confirmation-codes")).get("post"));
        Map<String, Object> issueResponses = map(issueOperation.get("responses"));
        assertThat(issueResponses).containsKeys("201", "403", "404", "409", "410");
        assertThat(issueOperation.get("description").toString())
                .contains("MEETING_CODE_REISSUE_ISSUER_FORBIDDEN", "receivedAt >=");

        Map<String, Object> verifyPath = map(paths.get(
                "/meeting-cards/{cardId}/confirmation-codes/verify"));
        Map<String, Object> verifyOperation = map(verifyPath.get("post"));
        assertThat(verifyOperation.get("requestBody")).isNotNull();

        assertThat(schemas).containsKeys("ConfirmationCodeCreateResult",
                "ConfirmationCodeResult", "ConfirmationCodeVerifyRequest");
        Map<String, Object> createResult = map(schemas.get("ConfirmationCodeCreateResult"));
        assertThat(map(createResult.get("properties"))).containsKeys("code", "expiresAt");
        Map<String, Object> verifyRequest = map(schemas.get("ConfirmationCodeVerifyRequest"));
        assertThat(list(verifyRequest.get("required"))).containsExactly("code");
        Map<String, Object> result = map(schemas.get("ConfirmationCodeResult"));
        assertThat(map(result.get("properties")))
                .containsKeys("cardId", "status", "meetingId", "verificationMethod", "confirmedAt");
    }

    @Test
    void runtimeOpenApiExposesConfirmationCodePathsAndSchemas() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/meeting-cards/{cardId}/confirmation-codes'].post")
                        .exists())
                .andExpect(jsonPath(
                        "$.paths['/meeting-cards/{cardId}/confirmation-codes'].post.description")
                        .value(org.hamcrest.Matchers.containsString(
                                "MEETING_CODE_REISSUE_ISSUER_FORBIDDEN")))
                .andExpect(jsonPath(
                        "$.paths['/meeting-cards/{cardId}/confirmation-codes/verify'].post").exists())
                .andExpect(jsonPath(
                        "$.paths['/meeting-cards/{cardId}/confirmation-codes/confirm'].post").exists())
                .andExpect(jsonPath("$.components.schemas.ConfirmationCodeCreateResult").exists())
                .andExpect(jsonPath("$.components.schemas.ConfirmationCodeResult").exists())
                .andExpect(jsonPath("$.components.schemas.ConfirmationCodeVerifyRequest").exists())
                .andExpect(jsonPath(
                        "$.components.schemas.ConfirmationCodeCreateResult.properties.code").exists())
                .andExpect(jsonPath(
                        "$.components.schemas.ConfirmationCodeCreateResult.properties.expiresAt").exists())
                .andExpect(jsonPath(
                        "$.components.schemas.ConfirmationCodeVerifyRequest.required").exists());
    }

    @Test
    void reissueIssuerErrorIsCodeSpecificForbiddenWithoutGpsWording() {
        assertThat(ErrorCode.MEETING_CODE_REISSUE_ISSUER_FORBIDDEN.getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ErrorCode.MEETING_CODE_REISSUE_ISSUER_FORBIDDEN.getDescription())
                .contains("최초 확인 코드 발급자")
                .doesNotContain("위치를 제출", "GPS");
        assertThat(ErrorCode.MEETING_TIME_WINDOW_EXCEEDED.getDescription())
                .doesNotContain("위치를 제출", "GPS");
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
