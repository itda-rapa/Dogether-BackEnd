package itda.chat;

import static org.assertj.core.api.Assertions.assertThat;

import itda.chat.dto.event.ChatMessageEvent;
import itda.chat.dto.response.ChatMessageResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ChatMessageEventTest {

    @Test
    void cardEventCarriesMeetingCardId() {
        ChatMessageResponse response = new ChatMessageResponse(
                1L, 2L, "PET", 3L, "몽이", "CARD", null,
                77L, "meeting-card:77:created", Instant.parse("2026-08-13T00:00:00Z"));

        assertThat(ChatMessageEvent.from(response).meetingCardId()).isEqualTo(77L);
    }
}
