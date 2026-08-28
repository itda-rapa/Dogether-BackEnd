package itda.route.service;

import itda.map.dto.NeighborhoodRequest;
import itda.map.service.MapService;
import itda.node.domain.NetworkNode;
import itda.node.repository.NodeRepository;
import itda.route.dto.RouteResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 경로 비용과 완전히 분리된 참고 환경정보 조회기.
 * 외부 API 실패는 경로 조회 실패로 전파하지 않고 부분/불가 상태로 변환한다.
 */
@Service
public class RouteEnvironmentService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Logger log = LoggerFactory.getLogger(RouteEnvironmentService.class);
    private static final DateTimeFormatter KMA_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter KMA_TIME = DateTimeFormatter.ofPattern("HHmm");
    private static final DateTimeFormatter KMA_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final int[] SHORT_TERM_BASE_HOURS = {2, 5, 8, 11, 14, 17, 20, 23};
    private static final int[] MID_TERM_BASE_HOURS = {6, 18};

    private final NodeRepository nodeRepository;
    private final MapService mapService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient = environmentRestClient();
    private final String kmaBaseUrl;
    private final String kmaKey;
    private final String kmaShortTermPath;
    private final String kmaMidTermPath;
    private final Duration kmaShortTermReleaseDelay;
    private final String airBaseUrl;
    private final String airKey;

    public RouteEnvironmentService(
            NodeRepository nodeRepository,
            MapService mapService,
            ObjectMapper objectMapper,
            @Value("${app.route.environment.kma-api-base-url:https://apihub.kma.go.kr}") String kmaBaseUrl,
            @Value("${app.route.environment.kma-api-key:}") String kmaKey,
            @Value("${app.route.environment.kma-short-term-path:/api/typ02/openApi/VilageFcstInfoService_2.0/getVilageFcst}") String kmaShortTermPath,
            @Value("${app.route.environment.kma-mid-term-path:/api/typ02/openApi/MidFcstInfoService/getMidLandFcst}") String kmaMidTermPath,
            @Value("${app.route.environment.kma-short-term-release-delay:15m}") Duration kmaShortTermReleaseDelay,
            @Value("${app.route.environment.air-korea-api-base-url:https://apis.data.go.kr/B552584}") String airBaseUrl,
            @Value("${app.route.environment.air-korea-api-key:}") String airKey
    ) {
        this.nodeRepository = nodeRepository;
        this.mapService = mapService;
        this.objectMapper = objectMapper;
        this.kmaBaseUrl = kmaBaseUrl;
        this.kmaKey = kmaKey;
        this.kmaShortTermPath = kmaShortTermPath;
        this.kmaMidTermPath = kmaMidTermPath;
        this.kmaShortTermReleaseDelay = kmaShortTermReleaseDelay;
        this.airBaseUrl = airBaseUrl;
        this.airKey = airKey;
    }

    public JsonNode load(RouteResponse route) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("departureAt", route.departureAt().toString());
        result.put("informationalOnly", true);
        if (kmaKey.isBlank() && airKey.isBlank()) {
            result.put("status", "NOT_CONFIGURED");
            result.putNull("weather");
            result.putNull("airQuality");
            return result;
        }

        try {
            NetworkNode node = nodeRepository.findFirstByNodeId(route.startNodeId()).orElseThrow();
            if (node.getGeom() == null) throw new IllegalStateException("Route start node geometry is missing");
            BigDecimal longitude = BigDecimal.valueOf(node.getGeom().getX());
            BigDecimal latitude = BigDecimal.valueOf(node.getGeom().getY());
            var neighborhood = mapService.getNeighborhood(new NeighborhoodRequest(
                    longitude.toPlainString(), latitude.toPlainString()));
            String province = neighborhood.getSidoName();
            JsonNode weather = safelyLoadWeather(route.departureAt(), province, longitude, latitude);
            JsonNode air = safelyLoadAirQuality(province);
            result.set("weather", weather);
            result.set("airQuality", air);
            result.put("status", availability(weather, air));
        } catch (Exception exception) {
            result.put("status", "UNAVAILABLE");
            result.put("message", "환경정보 제공기관 응답을 조회하지 못했습니다.");
            result.putNull("weather");
            result.putNull("airQuality");
        }
        return result;
    }

    private JsonNode safelyLoadWeather(Instant departureAt, String province,
                                       BigDecimal longitude, BigDecimal latitude) {
        try {
            return weather(departureAt, province, longitude, latitude);
        } catch (HttpClientErrorException.Forbidden exception) {
            log.warn("KMA forecast request was forbidden; API usage approval may be required");
            return unavailable("KMA_APPLICATION_REQUIRED");
        } catch (HttpClientErrorException.Unauthorized exception) {
            log.warn("KMA forecast authentication was rejected");
            return unavailable("KEY_REJECTED");
        } catch (RestClientResponseException exception) {
            log.warn("KMA forecast provider returned HTTP status {}", exception.getStatusCode().value());
            return unavailable("PROVIDER_ERROR");
        } catch (Exception exception) {
            log.warn("KMA forecast provider failed ({})", exception.getClass().getSimpleName());
            return unavailable("PROVIDER_ERROR");
        }
    }

    private JsonNode safelyLoadAirQuality(String province) {
        try {
            return airQuality(province);
        } catch (HttpClientErrorException.Forbidden exception) {
            return unavailable("AIR_SERVICE_KEY_REJECTED");
        } catch (Exception exception) {
            return unavailable("PROVIDER_ERROR");
        }
    }

    private JsonNode weather(Instant departureAt, String province,
                             BigDecimal longitude, BigDecimal latitude) {
        if (kmaKey.isBlank()) return unavailable("NOT_CONFIGURED");
        LocalDate today = LocalDate.now(SEOUL);
        LocalDate target = departureAt.atZone(SEOUL).toLocalDate();
        String coverage = forecastCoverage(today, target);
        if ("OUT_OF_RANGE".equals(coverage)) return unavailable("OUT_OF_RANGE");
        if ("SHORT_TERM".equals(coverage)) {
            return shortTermWeather(departureAt, latitude.doubleValue(), longitude.doubleValue());
        }
        return midTermWeather(departureAt, province, longitude.doubleValue());
    }

    private JsonNode shortTermWeather(Instant departureAt, double latitude, double longitude) {
        Grid grid = kmaGrid(latitude, longitude);
        LocalDateTime base = latestShortTermBase(LocalDateTime.now(SEOUL), kmaShortTermReleaseDelay);
        JsonNode payload = restClient.get().uri(uri -> uri
                        .scheme(kmaBaseUrl.startsWith("https") ? "https" : "http")
                        .host(host(kmaBaseUrl))
                        .path(kmaShortTermPath)
                        .queryParam("pageNo", 1)
                        .queryParam("numOfRows", 1000)
                        .queryParam("dataType", "JSON")
                        .queryParam("base_date", base.format(KMA_DATE))
                        .queryParam("base_time", base.format(KMA_TIME))
                        .queryParam("nx", grid.x())
                        .queryParam("ny", grid.y())
                        .queryParam("authKey", kmaKey)
                        .build())
                .retrieve().body(JsonNode.class);
        JsonNode error = kmaPayloadError(payload);
        if (error != null) return error;
        return mapShortTermForecast(payload, departureAt, grid, base);
    }

    private JsonNode midTermWeather(Instant departureAt, String province, double longitude) {
        LocalDateTime target = departureAt.atZone(SEOUL).toLocalDateTime();
        LocalDateTime base = latestMidTermBase(LocalDateTime.now(SEOUL), kmaShortTermReleaseDelay);
        String region = kmaRegion(province, longitude);
        JsonNode payload = restClient.get().uri(uri -> uri
                        .scheme(kmaBaseUrl.startsWith("https") ? "https" : "http")
                        .host(host(kmaBaseUrl))
                        .path(kmaMidTermPath)
                        .queryParam("pageNo", 1)
                        .queryParam("numOfRows", 10)
                        .queryParam("dataType", "JSON")
                        .queryParam("regId", region)
                        .queryParam("tmFc", base.format(KMA_DATE_TIME))
                        .queryParam("authKey", kmaKey)
                        .build())
                .retrieve().body(JsonNode.class);
        JsonNode error = kmaPayloadError(payload);
        if (error != null) return error;
        return mapMidTermForecast(payload, target, region, base);
    }

    JsonNode mapMidTermForecast(JsonNode payload, LocalDateTime target,
                                String region, LocalDateTime base) {
        JsonNode item = payload == null ? null : payload.at("/response/body/items/item/0");
        if (item == null || item.isMissingNode() || item.isNull()) return unavailable("PROVIDER_ERROR");
        long day = ChronoUnit.DAYS.between(base.toLocalDate(), target.toLocalDate());
        if (day < 4 || day > 10) return unavailable("OUT_OF_RANGE");
        boolean halfDay = day <= 7;
        String suffix = halfDay ? (target.getHour() < 12 ? "Am" : "Pm") : "";
        String weather = item.path("wf" + day + suffix).asText("");
        String rainProbability = item.path("rnSt" + day + suffix).asText("");
        if (weather.isBlank() && rainProbability.isBlank()) return unavailable("PROVIDER_ERROR");

        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "AVAILABLE");
        result.put("provider", "KMA_API_HUB");
        result.put("forecastType", "MID_TERM");
        result.put("coverage", "DAY_4_TO_10");
        result.put("regionCode", region);
        result.put("baseAt", base.atZone(SEOUL).toInstant().toString());
        result.put("forecastAt", target.atZone(SEOUL).toInstant().toString());
        putText(result, "weather", weather);
        putDecimal(result, "precipitationProbabilityPercent", rainProbability);
        StringBuilder summary = new StringBuilder(weather);
        if (!rainProbability.isBlank()) appendSummary(summary, "강수확률 " + rainProbability + "%");
        result.put("summary", summary.toString());
        return result;
    }

    private JsonNode airQuality(String province) {
        if (airKey.isBlank()) return unavailable("NOT_CONFIGURED");
        JsonNode payload = restClient.get().uri(uri -> uri
                        .scheme(airBaseUrl.startsWith("https") ? "https" : "http")
                        .host(host(airBaseUrl))
                        .path("/B552584/ArpltnInforInqireSvc/getCtprvnRltmMesureDnsty")
                        .queryParam("serviceKey", airKey)
                        .queryParam("returnType", "json")
                        .queryParam("numOfRows", 200)
                        .queryParam("pageNo", 1)
                        .queryParam("sidoName", airProvince(province))
                        .queryParam("ver", "1.0")
                        .build())
                .retrieve().body(JsonNode.class);
        JsonNode items = payload == null ? null : payload.at("/response/body/items");
        ObjectNode node = objectMapper.createObjectNode();
        node.put("provider", "AIR_KOREA");
        node.put("basis", "LATEST_OBSERVATION_NOT_FUTURE_FORECAST");
        if (items == null || !items.isArray() || items.isEmpty()) {
            node.put("status", "UNAVAILABLE");
            return node;
        }
        Average pm10 = average(items, "pm10Value");
        Average pm25 = average(items, "pm25Value");
        node.put("status", "AVAILABLE");
        if (pm10.count() > 0) node.put("pm10", pm10.value()); else node.putNull("pm10");
        if (pm25.count() > 0) node.put("pm25", pm25.value()); else node.putNull("pm25");
        node.put("stationCount", Math.max(pm10.count(), pm25.count()));
        node.put("observedAt", items.get(0).path("dataTime").asText(""));
        return node;
    }

    private Average average(JsonNode items, String field) {
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (JsonNode item : items) {
            String value = item.path(field).asText("");
            try {
                sum = sum.add(new BigDecimal(value));
                count++;
            } catch (NumberFormatException ignored) {
                // '-' and communication-error values are excluded from the provincial mean.
            }
        }
        return new Average(count == 0 ? null : sum.divide(BigDecimal.valueOf(count), 1,
                RoundingMode.HALF_UP), count);
    }

    private JsonNode unavailable(String reason) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("status", reason);
        return node;
    }

    private String availability(JsonNode weather, JsonNode air) {
        boolean weatherAvailable = "AVAILABLE".equals(weather.path("status").asText());
        boolean airAvailable = "AVAILABLE".equals(air.path("status").asText());
        if (weatherAvailable && airAvailable) return "AVAILABLE";
        if (weatherAvailable || airAvailable) return "PARTIAL";
        return "UNAVAILABLE";
    }

    JsonNode mapShortTermForecast(JsonNode payload, Instant departureAt, Grid grid,
                                  LocalDateTime base) {
        JsonNode items = payload == null ? null : payload.at("/response/body/items/item");
        if (items == null || !items.isArray() || items.isEmpty()) return unavailable("PROVIDER_ERROR");

        Map<LocalDateTime, Map<String, String>> byForecastTime = new LinkedHashMap<>();
        for (JsonNode item : items) {
            String date = item.path("fcstDate").asText("");
            String time = item.path("fcstTime").asText("");
            String category = item.path("category").asText("");
            if (date.length() != 8 || time.length() != 4 || category.isBlank()) continue;
            try {
                LocalDateTime forecastAt = LocalDateTime.parse(date + time, KMA_DATE_TIME);
                byForecastTime.computeIfAbsent(forecastAt, ignored -> new LinkedHashMap<>())
                        .put(category, item.path("fcstValue").asText(""));
            } catch (RuntimeException ignored) {
                // A malformed provider row is skipped while other valid rows remain usable.
            }
        }
        if (byForecastTime.isEmpty()) return unavailable("PROVIDER_ERROR");

        LocalDateTime target = departureAt.atZone(SEOUL).toLocalDateTime();
        LocalDateTime forecastAt = byForecastTime.keySet().stream()
                .min((left, right) -> Long.compare(
                        absoluteSeconds(left, target), absoluteSeconds(right, target)))
                .orElseThrow();
        Map<String, String> values = byForecastTime.get(forecastAt);
        String skyCode = values.getOrDefault("SKY", "");
        String precipitationTypeCode = values.getOrDefault("PTY", "");
        String sky = skyLabel(skyCode);
        String precipitationType = precipitationLabel(precipitationTypeCode);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "AVAILABLE");
        result.put("provider", "KMA_API_HUB");
        result.put("forecastType", "SHORT_TERM");
        result.put("coverage", "DAY_0_TO_3");
        result.put("baseAt", base.atZone(SEOUL).toInstant().toString());
        result.put("forecastAt", forecastAt.atZone(SEOUL).toInstant().toString());
        result.put("gridX", grid.x());
        result.put("gridY", grid.y());
        putDecimal(result, "temperatureC", values.get("TMP"));
        putDecimal(result, "precipitationProbabilityPercent", values.get("POP"));
        putDecimal(result, "humidityPercent", values.get("REH"));
        putDecimal(result, "windSpeedMps", values.get("WSD"));
        putText(result, "precipitationAmount", values.get("PCP"));
        putText(result, "skyCode", skyCode);
        putText(result, "sky", sky);
        putText(result, "precipitationTypeCode", precipitationTypeCode);
        putText(result, "precipitationType", precipitationType);
        result.put("summary", shortTermSummary(sky, precipitationType,
                values.get("TMP"), values.get("POP")));
        return result;
    }

    JsonNode kmaPayloadError(JsonNode payload) {
        if (payload == null) return unavailable("PROVIDER_ERROR");
        String code = payload.at("/response/header/resultCode").asText("");
        if ("00".equals(code)) return null;
        String message = payload.at("/response/header/resultMsg").asText("");
        if ("30".equals(code) || "31".equals(code) || "32".equals(code)
                || message.toUpperCase().contains("SERVICE KEY")) {
            return unavailable("KEY_REJECTED");
        }
        if (message.toUpperCase().contains("APPLICATION")) {
            return unavailable("KMA_APPLICATION_REQUIRED");
        }
        return unavailable("PROVIDER_ERROR");
    }

    static LocalDateTime latestShortTermBase(LocalDateTime now, Duration releaseDelay) {
        LocalDateTime cutoff = now.minus(releaseDelay);
        for (int index = SHORT_TERM_BASE_HOURS.length - 1; index >= 0; index--) {
            LocalDateTime candidate = cutoff.toLocalDate().atTime(SHORT_TERM_BASE_HOURS[index], 0);
            if (!candidate.isAfter(cutoff)) return candidate;
        }
        return cutoff.toLocalDate().minusDays(1).atTime(23, 0);
    }

    static LocalDateTime latestMidTermBase(LocalDateTime now, Duration releaseDelay) {
        LocalDateTime cutoff = now.minus(releaseDelay);
        for (int index = MID_TERM_BASE_HOURS.length - 1; index >= 0; index--) {
            LocalDateTime candidate = cutoff.toLocalDate().atTime(MID_TERM_BASE_HOURS[index], 0);
            if (!candidate.isAfter(cutoff)) return candidate;
        }
        return cutoff.toLocalDate().minusDays(1).atTime(18, 0);
    }

    static String forecastCoverage(LocalDate today, LocalDate target) {
        long days = ChronoUnit.DAYS.between(today, target);
        if (days < 0 || days > 10) return "OUT_OF_RANGE";
        return days < 4 ? "SHORT_TERM" : "MID_TERM";
    }

    static Grid kmaGrid(double latitude, double longitude) {
        final double earthRadius = 6371.00877;
        final double gridSpacing = 5.0;
        final double standardLatitude1 = Math.toRadians(30.0);
        final double standardLatitude2 = Math.toRadians(60.0);
        final double originLongitude = Math.toRadians(126.0);
        final double originLatitude = Math.toRadians(38.0);
        final double originX = 43.0;
        final double originY = 136.0;

        double re = earthRadius / gridSpacing;
        double sn = Math.log(Math.cos(standardLatitude1) / Math.cos(standardLatitude2))
                / Math.log(Math.tan(Math.PI * 0.25 + standardLatitude2 * 0.5)
                / Math.tan(Math.PI * 0.25 + standardLatitude1 * 0.5));
        double sf = Math.pow(Math.tan(Math.PI * 0.25 + standardLatitude1 * 0.5), sn)
                * Math.cos(standardLatitude1) / sn;
        double ro = re * sf / Math.pow(Math.tan(Math.PI * 0.25 + originLatitude * 0.5), sn);
        double ra = re * sf / Math.pow(Math.tan(Math.PI * 0.25 + Math.toRadians(latitude) * 0.5), sn);
        double theta = Math.toRadians(longitude) - originLongitude;
        if (theta > Math.PI) theta -= 2.0 * Math.PI;
        if (theta < -Math.PI) theta += 2.0 * Math.PI;
        theta *= sn;
        int x = (int) Math.floor(ra * Math.sin(theta) + originX + 0.5);
        int y = (int) Math.floor(ro - ra * Math.cos(theta) + originY + 0.5);
        return new Grid(x, y);
    }

    private long absoluteSeconds(LocalDateTime left, LocalDateTime right) {
        return Math.abs(ChronoUnit.SECONDS.between(left, right));
    }

    private void putDecimal(ObjectNode node, String field, String value) {
        if (value == null || value.isBlank()) {
            node.putNull(field);
            return;
        }
        try {
            node.put(field, new BigDecimal(value));
        } catch (NumberFormatException ignored) {
            node.putNull(field);
        }
    }

    private void putText(ObjectNode node, String field, String value) {
        if (value == null || value.isBlank()) node.putNull(field); else node.put(field, value);
    }

    private String shortTermSummary(String sky, String precipitationType,
                                    String temperature, String precipitationProbability) {
        StringBuilder summary = new StringBuilder();
        if (!sky.isBlank()) summary.append(sky);
        if (!precipitationType.isBlank()) appendSummary(summary, precipitationType);
        if (temperature != null && !temperature.isBlank()) appendSummary(summary, temperature + "℃");
        if (precipitationProbability != null && !precipitationProbability.isBlank()) {
            appendSummary(summary, "강수확률 " + precipitationProbability + "%");
        }
        return summary.toString();
    }

    private void appendSummary(StringBuilder summary, String value) {
        if (!summary.isEmpty()) summary.append(" · ");
        summary.append(value);
    }

    private String skyLabel(String code) {
        return switch (code) {
            case "1" -> "맑음";
            case "3" -> "구름많음";
            case "4" -> "흐림";
            default -> "";
        };
    }

    private String precipitationLabel(String code) {
        return switch (code) {
            case "0" -> "강수 없음";
            case "1" -> "비";
            case "2" -> "비/눈";
            case "3" -> "눈";
            case "4" -> "소나기";
            case "5" -> "빗방울";
            case "6" -> "빗방울/눈날림";
            case "7" -> "눈날림";
            default -> "";
        };
    }

    private String kmaRegion(String province, double longitude) {
        if (province.startsWith("서울") || province.startsWith("인천") || province.startsWith("경기")) return "11B00000";
        if (province.startsWith("강원")) return longitude >= 128.2 ? "11D20000" : "11D10000";
        if (province.startsWith("충북")) return "11C10000";
        if (province.startsWith("대전") || province.startsWith("세종") || province.startsWith("충남")) return "11C20000";
        if (province.startsWith("전북")) return "11F10000";
        if (province.startsWith("광주") || province.startsWith("전남")) return "11F20000";
        if (province.startsWith("대구") || province.startsWith("경북")) return "11H10000";
        if (province.startsWith("부산") || province.startsWith("울산") || province.startsWith("경남")) return "11H20000";
        if (province.startsWith("제주")) return "11G00000";
        throw new IllegalArgumentException("Unsupported KMA province: " + province);
    }

    private String airProvince(String province) {
        return province.replace("특별시", "").replace("광역시", "")
                .replace("특별자치시", "").replace("특별자치도", "")
                .replace("도", "");
    }

    private String host(String url) {
        return java.net.URI.create(url).getHost();
    }

    private static RestClient environmentRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(4));
        return RestClient.builder().requestFactory(factory).build();
    }

    record Grid(int x, int y) { }

    private record Average(BigDecimal value, int count) { }
}
