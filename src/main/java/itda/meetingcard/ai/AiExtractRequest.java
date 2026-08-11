package itda.meetingcard.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * AI 서버 {@code POST /api/v1/meeting-drafts/extract} 요청 본문.
 */
public record AiExtractRequest(
        @JsonProperty("room_id") String roomId,
        @JsonProperty("reference_date") String referenceDate,
        List<AiExtractMessage> messages
) {
}