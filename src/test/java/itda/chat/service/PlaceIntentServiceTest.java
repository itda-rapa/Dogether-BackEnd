package itda.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import itda.chat.domain.ChatMessage;
import itda.chat.domain.ChatRoom;
import itda.chat.domain.ChatRoomParticipant;
import itda.chat.dto.response.PlaceIntentResponse;
import itda.chat.repository.ChatMessageRepository;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PlaceIntentServiceTest {

    @Test
    void showsHospitalPopupOnlyAfterAiConfirmsTheOwnTriggerMessage() {
        ActivePetQueryService activePets = mock(ActivePetQueryService.class);
        ChatQueryService chatQuery = mock(ChatQueryService.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        ChatRoomParticipantRepository participants = mock(ChatRoomParticipantRepository.class);
        RestClient.Builder clientBuilder = RestClient.builder().baseUrl("http://ai.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(clientBuilder).build();
        PlaceIntentService service = new PlaceIntentService(
                activePets, chatQuery, messages, participants, clientBuilder.build());

        ChatRoom room = ChatRoom.openChat("병원 정보", null, 11L, 10, true);
        ReflectionTestUtils.setField(room, "id", 7L);
        ChatMessage trigger = ChatMessage.fromPet(room, "근처 동물병원 찾아볼까?", 11L)
                .setId(99L)
                .setCreatedAt(Instant.parse("2026-08-26T01:00:00Z"));
        when(activePets.requireActivePet(101L)).thenReturn(
                new ActivePetContext(11L, 101L, "pet-11", "초코", null, true));
        when(messages.findById(99L)).thenReturn(Optional.of(trigger));
        when(participants.findByRoomId(7L)).thenReturn(List.of(
                ChatRoomParticipant.join(room, 11L)));
        when(messages.findContextUpTo(
                eq(7L), eq(99L), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(trigger));
        server.expect(requestTo("http://ai.test/api/v1/place-intent/decide"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"decision":"SHOW","place_type":"HOSPITAL","target_user_id":"11"}
                        """, MediaType.APPLICATION_JSON));

        PlaceIntentResponse response = service.decide(101L, 7L, 99L);

        assertThat(response.decision()).isEqualTo(PlaceIntentResponse.Decision.SHOW);
        assertThat(response.placeType()).isEqualTo(PlaceIntentResponse.PlaceType.HOSPITAL);
        assertThat(response.targetPetId()).isEqualTo(11L);
        server.verify();
    }
}
