package itda.route.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RouteEnvironmentServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RouteEnvironmentService service = new RouteEnvironmentService(
            null, null, objectMapper,
            "https://apihub.kma.go.kr", "test-key",
            "/api/typ02/openApi/VilageFcstInfoService_2.0/getVilageFcst",
            "/api/typ02/openApi/MidFcstInfoService/getMidLandFcst", Duration.ofMinutes(15),
            "https://apis.data.go.kr/B552584", "test-air-key");

    @Test
    void 서울_위경도를_기상청_동네예보_격자로_변환한다() {
        RouteEnvironmentService.Grid grid = RouteEnvironmentService.kmaGrid(37.5665, 126.9780);

        assertThat(grid.x()).isEqualTo(60);
        assertThat(grid.y()).isEqualTo(127);
    }

    @Test
    void 발표_지연을_고려해_사용_가능한_최신_단기예보_회차를_고른다() {
        Duration delay = Duration.ofMinutes(15);

        assertThat(RouteEnvironmentService.latestShortTermBase(
                LocalDateTime.of(2026, 8, 28, 5, 14), delay))
                .isEqualTo(LocalDateTime.of(2026, 8, 28, 2, 0));
        assertThat(RouteEnvironmentService.latestShortTermBase(
                LocalDateTime.of(2026, 8, 28, 5, 15), delay))
                .isEqualTo(LocalDateTime.of(2026, 8, 28, 5, 0));
        assertThat(RouteEnvironmentService.latestShortTermBase(
                LocalDateTime.of(2026, 8, 28, 1, 0), delay))
                .isEqualTo(LocalDateTime.of(2026, 8, 27, 23, 0));
    }

    @Test
    void 출발일까지의_일수로_단기와_중기_범위를_나눈다() {
        LocalDate today = LocalDate.of(2026, 8, 28);

        assertThat(RouteEnvironmentService.forecastCoverage(today, today)).isEqualTo("SHORT_TERM");
        assertThat(RouteEnvironmentService.forecastCoverage(today, today.plusDays(3))).isEqualTo("SHORT_TERM");
        assertThat(RouteEnvironmentService.forecastCoverage(today, today.plusDays(4))).isEqualTo("MID_TERM");
        assertThat(RouteEnvironmentService.forecastCoverage(today, today.plusDays(10))).isEqualTo("MID_TERM");
        assertThat(RouteEnvironmentService.forecastCoverage(today, today.minusDays(1))).isEqualTo("OUT_OF_RANGE");
        assertThat(RouteEnvironmentService.forecastCoverage(today, today.plusDays(11))).isEqualTo("OUT_OF_RANGE");
    }

    @Test
    void 발표_지연을_고려해_사용_가능한_최신_중기예보_회차를_고른다() {
        Duration delay = Duration.ofMinutes(15);

        assertThat(RouteEnvironmentService.latestMidTermBase(
                LocalDateTime.of(2026, 8, 28, 6, 14), delay))
                .isEqualTo(LocalDateTime.of(2026, 8, 27, 18, 0));
        assertThat(RouteEnvironmentService.latestMidTermBase(
                LocalDateTime.of(2026, 8, 28, 6, 15), delay))
                .isEqualTo(LocalDateTime.of(2026, 8, 28, 6, 0));
    }

    @Test
    void 중기예보에서_출발일과_오전오후에_맞는_필드를_선택한다() throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL_SERVICE"},
                "body":{"items":{"item":[{
                  "regId":"11B00000", "wf7Am":"맑음", "wf7Pm":"흐림",
                  "rnSt7Am":20, "rnSt7Pm":60
                }]}}}}
                """);

        JsonNode weather = service.mapMidTermForecast(payload,
                LocalDateTime.of(2026, 9, 4, 10, 0), "11B00000",
                LocalDateTime.of(2026, 8, 28, 6, 0));

        assertThat(weather.path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(weather.path("forecastType").asText()).isEqualTo("MID_TERM");
        assertThat(weather.path("weather").asText()).isEqualTo("맑음");
        assertThat(weather.path("precipitationProbabilityPercent").decimalValue())
                .isEqualByComparingTo("20");
        assertThat(weather.path("summary").asText()).isEqualTo("맑음 · 강수확률 20%");
    }

    @Test
    void 출발시각에_가장_가까운_예보를_표준_JSON으로_변환한다() throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL_SERVICE"},
                "body":{"items":{"item":[
                  {"fcstDate":"20260828","fcstTime":"1000","category":"SKY","fcstValue":"1"},
                  {"fcstDate":"20260828","fcstTime":"1000","category":"PTY","fcstValue":"0"},
                  {"fcstDate":"20260828","fcstTime":"1000","category":"TMP","fcstValue":"24"},
                  {"fcstDate":"20260828","fcstTime":"1000","category":"POP","fcstValue":"10"},
                  {"fcstDate":"20260828","fcstTime":"1000","category":"PCP","fcstValue":"강수없음"},
                  {"fcstDate":"20260828","fcstTime":"1000","category":"REH","fcstValue":"65"},
                  {"fcstDate":"20260828","fcstTime":"1000","category":"WSD","fcstValue":"2.3"},
                  {"fcstDate":"20260828","fcstTime":"1300","category":"SKY","fcstValue":"4"},
                  {"fcstDate":"20260828","fcstTime":"1300","category":"TMP","fcstValue":"27"}
                ]}}}}
                """);
        Instant departure = Instant.parse("2026-08-28T01:20:00Z"); // 10:20 KST

        JsonNode weather = service.mapShortTermForecast(payload, departure,
                new RouteEnvironmentService.Grid(60, 127), LocalDateTime.of(2026, 8, 28, 5, 0));

        assertThat(weather.path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(weather.path("forecastType").asText()).isEqualTo("SHORT_TERM");
        assertThat(weather.path("forecastAt").asText()).isEqualTo("2026-08-28T01:00:00Z");
        assertThat(weather.path("sky").asText()).isEqualTo("맑음");
        assertThat(weather.path("precipitationType").asText()).isEqualTo("강수 없음");
        assertThat(weather.path("temperatureC").decimalValue()).isEqualByComparingTo("24");
        assertThat(weather.path("precipitationProbabilityPercent").decimalValue())
                .isEqualByComparingTo("10");
        assertThat(weather.path("summary").asText())
                .isEqualTo("맑음 · 강수 없음 · 24℃ · 강수확률 10%");
    }

    @Test
    void 기상청_정상_HTTP_응답에_포함된_키_오류를_보존한다() throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {"response":{"header":{"resultCode":"30",
                "resultMsg":"SERVICE KEY IS NOT REGISTERED ERROR"}}}
                """);

        JsonNode error = service.kmaPayloadError(payload);

        assertThat(error.path("status").asText()).isEqualTo("KEY_REJECTED");
    }
}
