package itda.meetingcard.ai;

import itda.meetingcard.domain.MeetingCardType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/** AI 원시 후보를 도메인 결과로 동일하게 매핑한다. */
final class AiDraftResultMapper {

    private AiDraftResultMapper() {
    }

    static AiDraftResult map(List<AiExtractResponse> raw, ZoneId zoneId) {
        if (raw == null || raw.isEmpty()) {
            return AiDraftResult.empty();
        }
        return AiDraftResult.success(raw.stream()
                .map(response -> new AiDraftResult.Candidate(
                        mapCardType(response.meetingType()),
                        response.date(),
                        response.time(),
                        response.place(),
                        combineDateTime(response.date(), response.time(), zoneId)))
                .toList());
    }

    private static MeetingCardType mapCardType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MeetingCardType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant combineDateTime(String date, String time, ZoneId zoneId) {
        if (date == null || date.isBlank() || time == null || time.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zoneId)
                    .toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
