package itda.meetingcard.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import itda.meetingcard.domain.CardDraftFallbackReason;
import itda.meetingcard.domain.MeetingCardType;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MeetingCardAiAdapter / FixtureMeetingDraftAiClient")
class MeetingCardAiAdapterTest {

    private HttpServer server;

    // ──────────── 여섯 가지 결과 테이블 ────────────

    @Nested
    @DisplayName("Outcome 1: full or partial extraction")
    class FullExtraction {

        @Test
        @DisplayName("full extraction returns typed result with combined instant")
        void fullExtraction() {
            var fixture = new FixtureMeetingDraftAiClient()
                    .prepareFullExtraction("WALK", "2026-07-31", "19:00", "중앙공원");

            AiDraftResult result = fixture.extract(dummyCommand());

            assertThat(result.cardType()).isEqualTo(MeetingCardType.WALK);
            assertThat(result.date()).isEqualTo("2026-07-31");
            assertThat(result.time()).isEqualTo("19:00");
            assertThat(result.place()).isEqualTo("중앙공원");
            assertThat(result.combinedInstant()).isNotNull();
            assertThat(result.fallbackReason()).isNull();
        }

        @Test
        @DisplayName("partial extraction (no place) keeps nulls")
        void partialExtractionNoPlace() {
            var fixture = new FixtureMeetingDraftAiClient()
                    .prepareFullExtraction("PLAY", "2026-08-01", "10:00", null);

            AiDraftResult result = fixture.extract(dummyCommand());

            assertThat(result.cardType()).isEqualTo(MeetingCardType.PLAY);
            assertThat(result.date()).isEqualTo("2026-08-01");
            assertThat(result.time()).isEqualTo("10:00");
            assertThat(result.place()).isNull();
            assertThat(result.combinedInstant()).isNotNull();
            assertThat(result.fallbackReason()).isNull();
        }

        @Test
        @DisplayName("other meeting type — OTHER is kept")
        void otherTypeKept() {
            var fixture = new FixtureMeetingDraftAiClient()
                    .prepareFullExtraction("OTHER", "2026-07-30", "12:00", "동물병원");

            AiDraftResult result = fixture.extract(dummyCommand());

            assertThat(result.cardType()).isEqualTo(MeetingCardType.OTHER);
            assertThat(result.place()).isEqualTo("동물병원");
            assertThat(result.fallbackReason()).isNull();
        }
    }

    @Nested
    @DisplayName("Outcome 2: 200 with []")
    class EmptyArray {

        @Test
        @DisplayName("empty array returns all null with no fallback")
        void emptyArray() {
            var fixture = new FixtureMeetingDraftAiClient()
                    .prepareEmptyArray();

            AiDraftResult result = fixture.extract(dummyCommand());

            assertThat(result.cardType()).isNull();
            assertThat(result.date()).isNull();
            assertThat(result.time()).isNull();
            assertThat(result.place()).isNull();
            assertThat(result.combinedInstant()).isNull();
            assertThat(result.fallbackReason()).isNull();
        }
    }

    @Nested
    @DisplayName("Outcome 3: unknown/unmappable meeting_type")
    class UnknownType {

        @Test
        @DisplayName("unknown type yields null cardType, other fields survive")
        void unknownTypeNullCardType() {
            var fixture = new FixtureMeetingDraftAiClient()
                    .prepareUnknownType("SWIMMING", "2026-08-02", "14:00", "수영장");

            AiDraftResult result = fixture.extract(dummyCommand());

            assertThat(result.cardType()).isNull();
            assertThat(result.date()).isEqualTo("2026-08-02");
            assertThat(result.time()).isEqualTo("14:00");
            assertThat(result.place()).isEqualTo("수영장");
            assertThat(result.combinedInstant()).isNotNull();
            assertThat(result.fallbackReason()).isNull();
        }

        @Test
        @DisplayName("null meeting_type yields null cardType")
        void nullMeetingType() {
            var fixture = new FixtureMeetingDraftAiClient()
                    .prepareFullExtraction(null, "2026-08-03", "09:00", "공원");

            AiDraftResult result = fixture.extract(dummyCommand());

            assertThat(result.cardType()).isNull();
            assertThat(result.place()).isEqualTo("공원");
            assertThat(result.fallbackReason()).isNull();
        }
    }

    @Nested
    @DisplayName("Outcome 4: HTTP 504 or timeout → TIMEOUT")
    class Timeout {

        @Test
        @DisplayName("timeout yields TIMEOUT fallback with all null")
        void timeout() {
            var fixture = new FixtureMeetingDraftAiClient()
                    .prepareTimeout();

            AiDraftResult result = fixture.extract(dummyCommand());

            assertThat(result.fallbackReason()).isEqualTo(CardDraftFallbackReason.TIMEOUT);
            assertThat(result.cardType()).isNull();
            assertThat(result.date()).isNull();
            assertThat(result.time()).isNull();
            assertThat(result.place()).isNull();
            assertThat(result.combinedInstant()).isNull();
        }
    }

    @Nested
    @DisplayName("Outcome 5: HTTP 502 / connection failure / malformed → MODEL_ERROR")
    class ModelError {

        @Test
        @DisplayName("HTTP 502 yields MODEL_ERROR")
        void http502() {
            var fixture = new FixtureMeetingDraftAiClient()
                    .prepareModelError();

            AiDraftResult result = fixture.extract(dummyCommand());

            assertThat(result.fallbackReason()).isEqualTo(CardDraftFallbackReason.MODEL_ERROR);
            assertThat(result.cardType()).isNull();
            assertThat(result.date()).isNull();
            assertThat(result.time()).isNull();
            assertThat(result.place()).isNull();
            assertThat(result.combinedInstant()).isNull();
        }

        @Test
        @DisplayName("connection failure (RuntimeException) yields MODEL_ERROR")
        void connectionFailure() {
            var fixture = new FixtureMeetingDraftAiClient()
                    .prepareConnectionFailure();

            AiDraftResult result = fixture.extract(dummyCommand());

            assertThat(result.fallbackReason()).isEqualTo(CardDraftFallbackReason.MODEL_ERROR);
            assertThat(result.cardType()).isNull();
            assertThat(result.place()).isNull();
            assertThat(result.combinedInstant()).isNull();
        }
    }

    @Nested
    @DisplayName("Outcome 6: more than one array element is preserved")
    class TwoElements {

        @Test
        @DisplayName("two elements keep count and order without fallback")
        void twoElements() {
            var fixture = new FixtureMeetingDraftAiClient()
                    .prepareTwoElements();

            AiDraftResult result = fixture.extract(dummyCommand());

            assertThat(result.fallbackReason()).isNull();
            assertThat(result.candidates()).hasSize(2);
            assertThat(result.candidates()).extracting(AiDraftResult.Candidate::cardType)
                    .containsExactly(MeetingCardType.WALK, MeetingCardType.PLAY);
            assertThat(result.candidates()).extracting(AiDraftResult.Candidate::place)
                    .containsExactly("중앙공원", "댕댕카페");
        }
    }

    // ──────────── 추가 검증 ────────────

    @Nested
    @DisplayName("HOSPITAL mapping")
    class HospitalMapping {

        @Test
        @DisplayName("HOSPITAL survives as HOSPITAL, not collapsed into OTHER")
        void hospitalSurvives() {
            var fixture = new FixtureMeetingDraftAiClient()
                    .prepareHospitalExtraction("2026-08-04", "15:00", "동물병원");

            AiDraftResult result = fixture.extract(dummyCommand());

            assertThat(result.cardType()).isEqualTo(MeetingCardType.HOSPITAL);
            assertThat(result.place()).isEqualTo("동물병원");
            assertThat(result.combinedInstant()).isNotNull();
            assertThat(result.fallbackReason()).isNull();
        }
    }

    @Nested
    @DisplayName("Date + time → Asia/Seoul Instant")
    class DateTimeCombine {

        @Test
        @DisplayName("date + time combine to correct Instant using Asia/Seoul")
        void dateTimeCombineAsiaSeoul() {
            var fixture = new FixtureMeetingDraftAiClient(ZoneId.of("Asia/Seoul"))
                    .prepareFullExtraction("WALK", "2026-07-31", "19:00", "중앙공원");

            AiDraftResult result = fixture.extract(dummyCommand());

            // 2026-07-31T19:00 KST = UTC+9 → 2026-07-31T10:00:00Z
            Instant expected = Instant.parse("2026-07-31T10:00:00Z");
            assertThat(result.combinedInstant()).isEqualTo(expected);
        }

        @Test
        @DisplayName("date present + time null → combinedInstant null, place kept")
        void dateOnlyNoTime() {
            var fixture = new FixtureMeetingDraftAiClient()
                    .prepareDateOnly("WALK", "2026-07-31", "중앙공원");

            AiDraftResult result = fixture.extract(dummyCommand());

            assertThat(result.combinedInstant()).isNull();
            assertThat(result.date()).isEqualTo("2026-07-31");
            assertThat(result.time()).isNull();
            assertThat(result.place()).isEqualTo("중앙공원");
            assertThat(result.fallbackReason()).isNull();
        }

        @Test
        @DisplayName("time present + date null → combinedInstant null")
        void timeOnlyNoDate() {
            var fixture = new FixtureMeetingDraftAiClient()
                    .prepareFullExtraction("WALK", null, "19:00", "중앙공원");

            AiDraftResult result = fixture.extract(dummyCommand());

            assertThat(result.combinedInstant()).isNull();
            assertThat(result.date()).isNull();
            assertThat(result.time()).isEqualTo("19:00");
            assertThat(result.place()).isEqualTo("중앙공원");
            assertThat(result.fallbackReason()).isNull();
        }
    }

    // ──────────── 도우미 ────────────

    private AiDraftCommand dummyCommand() {
        return new AiDraftCommand("42", LocalDate.of(2026, 7, 30),
                List.of(new AiDraftCommand.AiMessage("1", "안녕", "2026-07-30T10:00:00+09:00")));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("production HTTP boundary: [null] becomes one MODEL_ERROR blank candidate")
    void productionAdapterNullCandidateBecomesModelErrorFallback() throws IOException {
        MeetingCardAiAdapter adapter = productionAdapterReturning("[null]");

        AiDraftResult result = adapter.extract(dummyCommand());

        assertModelErrorBlankCandidate(result);
    }

    @Test
    @DisplayName("production HTTP boundary: [valid, null] becomes one MODEL_ERROR blank candidate")
    void productionAdapterMixedNullCandidateBecomesModelErrorFallback() throws IOException {
        MeetingCardAiAdapter adapter = productionAdapterReturning("""
                [{"meeting_type":"WALK","date":"2026-07-31","time":"19:00","place":"공원"},null]
                """);

        AiDraftResult result = adapter.extract(dummyCommand());

        assertModelErrorBlankCandidate(result);
    }

    @Test
    @DisplayName("mapping RuntimeException is folded into MODEL_ERROR fallback")
    void mappingRuntimeExceptionDoesNotEscapeAdapter() {
        HttpMeetingDraftAiClient httpClient = org.mockito.Mockito.mock(HttpMeetingDraftAiClient.class);
        org.mockito.Mockito.when(httpClient.call(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AbstractList<AiExtractResponse>() {
                    @Override
                    public AiExtractResponse get(int index) {
                        throw new IllegalStateException("mapping failure");
                    }

                    @Override
                    public int size() {
                        return 1;
                    }
                });
        MeetingCardAiAdapter adapter = new MeetingCardAiAdapter(
                httpClient, ZoneId.of("Asia/Seoul"));

        AiDraftResult result = adapter.extract(dummyCommand());

        assertModelErrorBlankCandidate(result);
    }

    private MeetingCardAiAdapter productionAdapterReturning(String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/meeting-drafts/extract", exchange -> {
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new MeetingCardAiAdapter(
                new HttpMeetingDraftAiClient(baseUrl, Duration.ofSeconds(2)),
                ZoneId.of("Asia/Seoul"));
    }

    private void assertModelErrorBlankCandidate(AiDraftResult result) {
        assertThat(result.fallbackReason()).isEqualTo(CardDraftFallbackReason.MODEL_ERROR);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0)).isEqualTo(AiDraftResult.Candidate.blank());
    }
}
