package itda.meetingcard.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AI 서버 요청의 메시지 한 건.
 */
public record AiExtractMessage(
        @JsonProperty("sender") String sender,
        @JsonProperty("content") String content,
        @JsonProperty("sent_at") String sentAt
) {
}