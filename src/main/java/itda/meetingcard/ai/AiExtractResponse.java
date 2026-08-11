package itda.meetingcard.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AI 서버 {@code POST /api/v1/meeting-drafts/extract} 응답의 배열 요소 하나.
 *
 * <p>모든 필드는 null 가능하며, null 은 "추출되지 않음"을 의미한다.
 */
public record AiExtractResponse(
        @JsonProperty("meeting_type") String meetingType,
        @JsonProperty("date") String date,
        @JsonProperty("time") String time,
        @JsonProperty("place") String place
) {
}