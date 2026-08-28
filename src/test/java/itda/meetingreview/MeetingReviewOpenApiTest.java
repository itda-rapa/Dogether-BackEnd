package itda.meetingreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.Yaml;

/**
 * Runtime Swagger/OpenAPI 계약(#150). 실제 구동 중인 애플리케이션의 /v3/api-docs 가
 * 후기·발자국 엔드포인트와 요청/응답 shape 을 노출하는지 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MeetingReviewOpenApiTest {

    private static final Path STATIC_OPEN_API = Path.of("docs/spec/04_M1_OpenAPI.yaml");
    private static final Path M3_API_DOC = Path.of("docs/spec/M3/04_M3_API_상세명세.md");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void staticOpenApiAndM3DocumentReviewErrorsAndGrantedSemantics() throws Exception {
        Map<String, Object> openApi;
        try (InputStream input = Files.newInputStream(STATIC_OPEN_API)) {
            openApi = map(new Yaml().load(input));
        }
        Map<String, Object> paths = map(openApi.get("paths"));
        Map<String, Object> reviewPost = map(map(paths.get(
                "/meetings/{meetingId}/reviews")).get("post"));
        Map<String, Object> responses = map(reviewPost.get("responses"));

        assertThat(responses).containsKeys("201", "403", "404", "409");
        assertThat(map(responses.get("403")).get("description").toString())
                .contains("REVIEW_NOT_PARTICIPANT").doesNotContain("위치를 제출");
        assertThat(map(responses.get("404")).get("description").toString())
                .contains("MEETING_NOT_FOUND");
        assertThat(map(responses.get("409")).get("description").toString())
                .contains("REVIEW_CARD_NOT_OPEN").doesNotContain("위치를 제출");

        Map<String, Object> schemas = map(map(openApi.get("components")).get("schemas"));
        Map<String, Object> footprintProperties = map(
                map(schemas.get("ReviewFootprintResult")).get("properties"));
        assertThat(map(footprintProperties.get("granted")).get("description").toString())
                .contains("이번 HTTP 요청", "INSERT", "replay", "false");

        String m3 = Files.readString(M3_API_DOC);
        assertThat(m3).contains("403 REVIEW_NOT_PARTICIPANT", "404 MEETING_NOT_FOUND",
                "409 REVIEW_CARD_NOT_OPEN", "이번 HTTP 요청이 새 Footprint 행을 INSERT했는가",
                "Review와 Footprint는 항상 1:1 관계가 아니다");
    }

    @Test
    void runtimeOpenApiExposesReviewAndFootprintEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/meetings/{meetingId}/reviews'].post").exists())
                .andExpect(jsonPath("$.paths['/meetings/{meetingId}/reviews'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/meetings/{meetingId}/reviews'].post.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/meetings/{meetingId}/reviews'].post.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/meetings/{meetingId}/reviews'].post.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/meetings/{meetingId}/reviews'].post.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/meetings/{meetingId}/reviews'].post.responses['403'].description")
                        .value(containsString("REVIEW_NOT_PARTICIPANT")))
                .andExpect(jsonPath("$.paths['/meetings/{meetingId}/reviews'].post.responses['404'].description")
                        .value(containsString("MEETING_NOT_FOUND")))
                .andExpect(jsonPath("$.paths['/meetings/{meetingId}/reviews'].post.responses['409'].description")
                        .value(containsString("REVIEW_CARD_NOT_OPEN")))
                .andExpect(jsonPath("$.paths['/footprints'].get").exists())
                .andExpect(jsonPath("$.paths['/footprints'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/footprints'].get.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/footprints'].get.responses['403']").exists());
    }

    @Test
    void runtimeOpenApiExposesReviewCommandAndFootprintResultContract() throws Exception {
        String command = "$.components.schemas.MeetingReviewSubmitCommand";

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(command + ".required")
                        .value(org.hamcrest.Matchers.hasItem("clientRequestId")))
                .andExpect(jsonPath(command + ".required")
                        .value(org.hamcrest.Matchers.hasItem("placeTag")))
                .andExpect(jsonPath(command + ".properties.clientRequestId.type").value("string"))
                .andExpect(jsonPath(command + ".properties.placeTag.type").value("string"))
                .andExpect(jsonPath(command + ".properties.placeTag.maxLength").value(30))
                .andExpect(jsonPath(command + ".properties.content.type").value("string"))
                .andExpect(jsonPath(command + ".properties.content.maxLength").value(500))
                .andExpect(jsonPath("$.components.schemas.ReviewFootprintResult.properties.granted.type")
                        .value("boolean"))
                .andExpect(jsonPath("$.components.schemas.ReviewFootprintResult.properties.granted.description")
                        .value(containsString("이번 HTTP 요청")))
                .andExpect(jsonPath("$.components.schemas.ReviewFootprintResult.properties.duplicateDay.type")
                        .value("boolean"))
                .andExpect(jsonPath("$.components.schemas.ReviewFootprintResult.properties.footprintId")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.ReviewFootprintResult.properties.earnedDate")
                        .exists());
    }

    @Test
    void runtimeOpenApiExamplesMatchM3Contract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/meetings/{meetingId}/reviews'].post.requestBody.content['application/json'].example.clientRequestId")
                        .value("9eb374ad-e81d-433a-8934-93faf399d48e"))
                .andExpect(jsonPath("$.paths['/meetings/{meetingId}/reviews'].post.requestBody.content['application/json'].example.placeTag")
                        .value("공원"))
                .andExpect(jsonPath("$.paths['/meetings/{meetingId}/reviews'].post.responses['201'].content['application/json'].example.data.placeTag")
                        .value("공원"))
                .andExpect(jsonPath("$.paths['/meetings/{meetingId}/reviews'].post.responses['201'].content['application/json'].example.data.footprint.granted")
                        .value(true))
                .andExpect(jsonPath("$.paths['/meetings/{meetingId}/reviews'].post.responses['201'].content['application/json'].example.data.footprint.duplicateDay")
                        .value(false))
                .andExpect(jsonPath("$.paths['/footprints'].get.responses['200'].content['application/json'].example.data.items[0].counterpartPet.nickname")
                        .value("초코"))
                .andExpect(jsonPath("$.paths['/footprints'].get.responses['200'].content['application/json'].example.data.page.hasNext")
                        .value(false));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
