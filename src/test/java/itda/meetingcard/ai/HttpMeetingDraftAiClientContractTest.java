package itda.meetingcard.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import itda.meetingcard.domain.CardDraftFallbackReason;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpMeetingDraftAiClient} 를 실제 HTTP 로 실행하는 계약 테스트.
 *
 * <p>어댑터 테스트는 이 클라이언트를 대역으로 바꿔 끼우므로 판단 로직만 본다. 그래서 정작
 * 와이어 포맷 — snake_case 필드명, room_id 가 문자열인지, 응답이 배열인지, 502·504 매핑이
 * 실제 HTTP 상태에서 동작하는지 — 는 아무도 확인하지 않는다. 여기서 그걸 본다.
 *
 * <p>AI 서버 없이도 돌아야 하므로 JDK 내장 HTTP 서버를 포트 0 으로 띄운다. 새 의존성이
 * 필요 없고, 실제 RestClient·Jackson 경로가 그대로 실행된다.
 */
class HttpMeetingDraftAiClientContractTest {

    private HttpServer server;
    private String baseUrl;

    /** 서버가 받은 요청 본문. 직렬화 결과를 그대로 검사하기 위해 보관한다. */
    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpMeetingDraftAiClient client() {
        return new HttpMeetingDraftAiClient(baseUrl, Duration.ofSeconds(5));
    }

    private HttpMeetingDraftAiClient clientWithTimeout(Duration timeout) {
        return new HttpMeetingDraftAiClient(baseUrl, timeout);
    }

    /** 지정한 상태와 본문으로 응답하는 핸들러를 붙이고 서버를 시작한다. */
    private void respondWith(int status, String body) {
        server.createContext("/api/v1/meeting-drafts/extract", exchange -> {
            receivedBody.set(readBody(exchange));
            byte[] payload = body == null
                    ? new byte[0]
                    : body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static AiExtractRequest sampleRequest() {
        return new AiExtractRequest(
                "123",
                "2026-07-30",
                List.of(new AiExtractMessage(
                        "11",
                        "내일 저녁 7시에 중앙공원에서 산책할까요?",
                        "2026-07-30T18:00:00+09:00")));
    }

    // ── 요청 직렬화 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("요청 본문이 snake_case 로 직렬화되고 room_id 는 문자열이다")
    void requestIsSerializedAsSnakeCaseWithStringRoomId() {
        respondWith(200, "[]");

        client().call(sampleRequest());

        String body = receivedBody.get();
        assertThat(body).contains("\"room_id\":\"123\"");
        assertThat(body).contains("\"reference_date\":\"2026-07-30\"");
        assertThat(body).contains("\"sent_at\":\"2026-07-30T18:00:00+09:00\"");
        assertThat(body).contains("\"sender\":\"11\"");
        // camelCase 로 새면 AI 가 필드를 못 읽는다.
        assertThat(body).doesNotContain("roomId");
        assertThat(body).doesNotContain("referenceDate");
        assertThat(body).doesNotContain("sentAt");
        // room- 접두사를 붙이지 않기로 확정했다.
        assertThat(body).doesNotContain("room-");
    }

    // ── 응답 역직렬화 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("배열 응답이 AiExtractResponse 로 역직렬화된다")
    void arrayResponseIsDeserialized() {
        respondWith(200, """
                [{"meeting_type":"WALK","date":"2026-07-31","time":"19:00","place":"중앙공원"}]
                """);

        List<AiExtractResponse> result = client().call(sampleRequest());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).meetingType()).isEqualTo("WALK");
        assertThat(result.get(0).date()).isEqualTo("2026-07-31");
        assertThat(result.get(0).time()).isEqualTo("19:00");
        assertThat(result.get(0).place()).isEqualTo("중앙공원");
    }

    @Test
    @DisplayName("HOSPITAL 도 그대로 역직렬화된다")
    void hospitalIsDeserialized() {
        respondWith(200, """
                [{"meeting_type":"HOSPITAL","date":"2026-07-31","time":"10:00","place":"동물병원"}]
                """);

        assertThat(client().call(sampleRequest()).get(0).meetingType()).isEqualTo("HOSPITAL");
    }

    @Test
    @DisplayName("빈 배열은 빈 리스트가 된다")
    void emptyArrayBecomesEmptyList() {
        respondWith(200, "[]");

        assertThat(client().call(sampleRequest())).isEmpty();
    }

    @Test
    @DisplayName("필드가 일부 null 이어도 역직렬화된다")
    void partialFieldsAreDeserialized() {
        respondWith(200, """
                [{"meeting_type":"WALK","date":"2026-07-31","time":null,"place":"중앙공원"}]
                """);

        AiExtractResponse elem = client().call(sampleRequest()).get(0);
        assertThat(elem.time()).isNull();
        assertThat(elem.place()).isEqualTo("중앙공원");
    }

    @Test
    @DisplayName("모르는 필드가 와도 깨지지 않는다")
    void unknownFieldsDoNotBreakDeserialization() {
        respondWith(200, """
                [{"meeting_type":"WALK","date":"2026-07-31","time":"19:00","place":"공원",
                  "confidence":0.9,"model":"gemma"}]
                """);

        assertThat(client().call(sampleRequest()).get(0).meetingType()).isEqualTo("WALK");
    }

    @Test
    @DisplayName("원소가 둘이면 둘 다 그대로 돌려준다. 판단은 어댑터가 한다")
    void twoElementsAreReturnedAsIs() {
        respondWith(200, """
                [{"meeting_type":"WALK","date":"2026-07-31","time":"19:00","place":"공원"},
                 {"meeting_type":"PLAY","date":"2026-08-01","time":"14:00","place":"놀이터"}]
                """);

        assertThat(client().call(sampleRequest())).hasSize(2);
    }

    // ── 상태 코드 매핑 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("504 는 TIMEOUT 으로 매핑된다")
    void status504MapsToTimeout() {
        respondWith(504, "");

        assertThatThrownBy(() -> client().call(sampleRequest()))
                .isInstanceOf(HttpMeetingDraftAiClient.HttpMeetingDraftAiClientException.class)
                .extracting(e ->
                        ((HttpMeetingDraftAiClient.HttpMeetingDraftAiClientException) e)
                                .getFallbackReason())
                .isEqualTo(CardDraftFallbackReason.TIMEOUT);
    }

    @Test
    @DisplayName("502 는 MODEL_ERROR 로 매핑된다")
    void status502MapsToModelError() {
        respondWith(502, "");

        assertThatThrownBy(() -> client().call(sampleRequest()))
                .isInstanceOf(HttpMeetingDraftAiClient.HttpMeetingDraftAiClientException.class)
                .extracting(e ->
                        ((HttpMeetingDraftAiClient.HttpMeetingDraftAiClientException) e)
                                .getFallbackReason())
                .isEqualTo(CardDraftFallbackReason.MODEL_ERROR);
    }

    @Test
    @DisplayName("깨진 JSON 은 예외로 올라오고 어댑터가 MODEL_ERROR 로 접는다")
    void malformedJsonThrows() {
        respondWith(200, "{ this is not json");

        assertThatThrownBy(() -> client().call(sampleRequest()))
                .isInstanceOf(RuntimeException.class);
    }

    // ── 타임아웃 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("읽기 타임아웃이 실제로 걸린다")
    void readTimeoutIsEnforced() {
        server.createContext("/api/v1/meeting-drafts/extract", exchange -> {
            try {
                // 클라이언트 타임아웃보다 오래 붙잡는다.
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] payload = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();

        // 300ms 로 줄여 테스트가 오래 걸리지 않게 한다. 프로덕션 값은 5초다.
        assertThatThrownBy(() -> clientWithTimeout(Duration.ofMillis(300)).call(sampleRequest()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("연결 실패는 예외로 올라온다")
    void connectionFailureThrows() {
        // 서버를 시작하지 않아 아무도 응답하지 않는 포트로 요청한다.
        // 소켓은 바인드돼 있어 즉시 거부되지 않을 수 있으므로 짧은 타임아웃을 쓴다.
        assertThatThrownBy(() -> clientWithTimeout(Duration.ofMillis(300)).call(sampleRequest()))
                .isInstanceOf(RuntimeException.class);
    }
}
