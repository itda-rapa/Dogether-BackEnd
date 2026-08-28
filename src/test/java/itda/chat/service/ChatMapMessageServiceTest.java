package itda.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.chat.domain.ChatMessage;
import itda.chat.domain.ChatRoom;
import itda.chat.domain.MessageType;
import itda.chat.domain.SenderType;
import itda.chat.dto.ChatMessageResult;
import itda.chat.dto.request.CreateChatMapMessageRequest;
import itda.chat.dto.response.ChatMapMessageResponse;
import itda.chat.dto.response.ChatMessageResponse;
import itda.chat.repository.ChatMessageRepository;
import itda.map.domain.CulturalFacilityCategory;
import itda.map.dto.CulturalFacilityResponse;
import itda.map.service.CulturalFacilityService;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class ChatMapMessageServiceTest {

    @Test
    void savesFacilitySnapshotWithStraightLineDistanceAndPublishesKafkaEvent() {
        ActivePetQueryService activePetService = mock(ActivePetQueryService.class);
        ChatQueryService chatQueryService = mock(ChatQueryService.class);
        ChatMessageRepository repository = mock(ChatMessageRepository.class);
        CulturalFacilityService facilityService = mock(CulturalFacilityService.class);
        ChatMessageService messageService = mock(ChatMessageService.class);
        ChatMessageResponseAssembler assembler = mock(ChatMessageResponseAssembler.class);
        ChatMessageEventPublisher publisher = mock(ChatMessageEventPublisher.class);
        ChatMapDistanceService distanceService = mock(ChatMapDistanceService.class);
        ChatMapMessageService service = new ChatMapMessageService(
                activePetService, chatQueryService, repository, facilityService,
                messageService, assembler, publisher, distanceService, new ObjectMapper());
        ActivePetContext actor = new ActivePetContext(3L, 1L, "tag", "몽이", null, true);
        when(activePetService.requireActivePet(1L)).thenReturn(actor);
        ChatRoom room = mock(ChatRoom.class);
        when(room.getId()).thenReturn(2L);
        ChatMessage trigger = mock(ChatMessage.class);
        when(trigger.getRoom()).thenReturn(room);
        when(trigger.getSenderType()).thenReturn(SenderType.PET);
        when(trigger.getType()).thenReturn(MessageType.TEXT);
        when(trigger.getSenderPetId()).thenReturn(3L);
        when(trigger.getBody()).thenReturn("근처 카페 어디 있어요?");
        when(repository.findById(10L)).thenReturn(Optional.of(trigger));
        when(repository.findByRoomIdAndMapTriggerMessageId(2L, 10L)).thenReturn(Optional.empty());
        when(facilityService.findNearest(
                CulturalFacilityCategory.CAFE, BigDecimal.valueOf(127), BigDecimal.valueOf(37.5)))
                .thenReturn(List.of(new CulturalFacilityResponse(
                        7, CulturalFacilityCategory.CAFE, "반려 카페", "서울", null,
                        null, null, BigDecimal.valueOf(127.01), BigDecimal.valueOf(37.51), 123.4)));
        ChatMessage stored = mock(ChatMessage.class);
        when(messageService.postMap(eq(2L), eq(3L), eq(10L), eq("CAFE"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ChatMessageResult(stored, true));
        ChatMessageResponse response = new ChatMessageResponse(
                11L, 2L, "PET", 3L, "몽이", "MAP", null,
                null, null, new ChatMapMessageResponse("CAFE", List.of()),
                null, "map:10", Instant.now());
        when(assembler.toResponse(stored, "몽이")).thenReturn(response);

        ChatMessageResponse result = service.create(1L, 2L, new CreateChatMapMessageRequest(
                10L, CulturalFacilityCategory.CAFE,
                BigDecimal.valueOf(127), BigDecimal.valueOf(37.5)));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(messageService).postMap(eq(2L), eq(3L), eq(10L), eq("CAFE"), json.capture());
        assertThat(json.getValue()).contains("반려 카페")
                .contains("\"distanceMeters\":123.4")
                .contains("\"averageDistanceMeters\":123.4")
                .contains("\"distanceParticipantCount\":1")
                .contains("\"distanceRank\":1")
                .doesNotContain("\"longitude\":127.0,\"latitude\":37.5");
        verify(distanceService).rememberLocation(
                0L, 3L, BigDecimal.valueOf(127), BigDecimal.valueOf(37.5));
        verify(publisher).publishAfterCommit(response);
        assertThat(result).isEqualTo(response);
    }
}
