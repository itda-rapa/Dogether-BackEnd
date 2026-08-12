package itda.meetingcard.ai;

import static org.assertj.core.api.Assertions.assertThat;

import itda.meetingcard.domain.CardDraftFallbackReason;
import itda.meetingcard.domain.MeetingCardType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MeetingCardAiAdapter / FixtureMeetingDraftAiClient")
class MeetingCardAiAdapterTest {

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
}
