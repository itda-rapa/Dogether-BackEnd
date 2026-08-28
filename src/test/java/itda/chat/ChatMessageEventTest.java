package itda.chat;

import static org.assertj.core.api.Assertions.assertThat;

import itda.chat.dto.event.ChatMessageEvent;
import itda.chat.dto.response.ChatMessageResponse;
import itda.chat.dto.response.ChatMapMessageResponse;
import itda.chat.dto.response.MapFacilitySnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatMessageEventTest {

    @Test
    void cardEventCarriesMeetingCardId() {
        ChatMessageResponse response = new ChatMessageResponse(
                1L, 2L, "PET", 3L, "몽이", "CARD", null,
                null, null, null, 77L, "meeting-card:77:created", Instant.parse("2026-08-13T00:00:00Z"));

        assertThat(ChatMessageEvent.from(response).meetingCardId()).isEqualTo(77L);
    }

    @Test
    void mapEventCarriesFacilitySnapshotWithoutUserCoordinates() {
        ChatMapMessageResponse map = new ChatMapMessageResponse(
                "CAFE",
                List.of(new MapFacilitySnapshot(
                        10, "반려 카페", "서울", null, null,
                        BigDecimal.valueOf(126.98), BigDecimal.valueOf(37.56),
                        42.5, 40.0, 2, 1)));
        ChatMessageResponse response = new ChatMessageResponse(
                2L, 2L, "PET", 3L, "몽이", "MAP", null,
                null, null, map, null, "map:1", Instant.now());

        ChatMessageEvent event = ChatMessageEvent.from(response);

        assertThat(event.map()).isEqualTo(map);
        assertThat(event.map().facilities()).hasSize(1);
    }
}
