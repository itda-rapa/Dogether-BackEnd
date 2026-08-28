package itda.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import itda.chat.domain.ChatMessage;
import itda.chat.domain.ChatRoom;
import itda.chat.repository.ChatMessageAttachmentRepository;
import itda.media.service.MediaService;
import itda.setlog.service.SetlogQueryService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChatMessageResponseAssemblerMapTest {

    @Test
    void restoresStoredFacilitySnapshotWithoutSenderLocation() {
        ChatRoom room = mock(ChatRoom.class);
        when(room.getId()).thenReturn(2L);
        ChatMessage message = ChatMessage.map(
                room, 3L, 10L, "CAFE",
                "[{\"facilityId\":7,\"name\":\"반려 카페\",\"address\":\"서울\","
                        + "\"telephone\":null,\"operatingHours\":null,"
                        + "\"longitude\":126.98,\"latitude\":37.56}]",
                "map:10");
        ChatMessageResponseAssembler assembler = new ChatMessageResponseAssembler(
                mock(ChatMessageAttachmentRepository.class),
                mock(MediaService.class),
                mock(SetlogQueryService.class),
                new SharedSetlogResponseMapper(),
                new ObjectMapper());

        var response = assembler.toResponse(message, "몽이");

        assertThat(response.type()).isEqualTo("MAP");
        assertThat(response.map().category()).isEqualTo("CAFE");
        assertThat(response.map().facilities()).hasSize(1);
        assertThat(response.map().facilities().getFirst().name()).isEqualTo("반려 카페");
    }
}
