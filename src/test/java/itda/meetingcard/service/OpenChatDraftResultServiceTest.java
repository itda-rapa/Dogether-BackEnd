package itda.meetingcard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.chat.domain.ChatRoom;
import itda.chat.domain.ChatRoomParticipant;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.meetingcard.domain.CardDraft;
import itda.meetingcard.domain.CardDraftParticipant;
import itda.meetingcard.dto.event.OpenChatDraftAiResultEvent;
import itda.meetingcard.dto.event.OpenChatDraftReadyEvent;
import itda.meetingcard.repository.CardDraftParticipantRepository;
import itda.meetingcard.repository.CardDraftRepository;
import itda.pet.repository.PetRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OpenChatDraftResultServiceTest {

    @Mock CardDraftRepository draftRepository;
    @Mock CardDraftParticipantRepository participantRepository;
    @Mock ChatRoomParticipantRepository roomParticipantRepository;
    @Mock PetRepository petRepository;

    private OpenChatDraftResultService service;

    @BeforeEach
    void setUp() {
        service = new OpenChatDraftResultService(
                draftRepository, participantRepository, roomParticipantRepository, petRepository);
    }

    @Test
    void persistsOnceAndNotifiesOnlySelectedPetOwners() {
        ChatRoom room = ChatRoom.openChat("산책", null, 11L, 10, true);
        List<ChatRoomParticipant> activeParticipants = List.of(
                ChatRoomParticipant.join(room, 11L),
                ChatRoomParticipant.join(room, 22L),
                ChatRoomParticipant.join(room, 33L));
        when(roomParticipantRepository.findByRoomId(7L)).thenReturn(activeParticipants);
        when(draftRepository.findByRequestIdOrderByCandidateIndexAsc("request-1"))
                .thenReturn(List.of());
        AtomicLong ids = new AtomicLong(100L);
        when(draftRepository.saveAndFlush(any(CardDraft.class))).thenAnswer(invocation -> {
            CardDraft draft = invocation.getArgument(0);
            ReflectionTestUtils.setField(draft, "id", ids.getAndIncrement());
            return draft;
        });
        PetRepository.PetOwnerRow owner11 = owner(11L, 101L);
        PetRepository.PetOwnerRow owner22 = owner(22L, 102L);
        when(petRepository.findOwnerRows(List.of(11L, 22L)))
                .thenReturn(List.of(owner11, owner22));

        OpenChatDraftAiResultEvent event = event();
        Map<Long, List<OpenChatDraftReadyEvent.Draft>> first = service.persist(event);

        assertThat(first).containsOnlyKeys(101L, 102L);
        assertThat(first.get(101L)).singleElement()
                .satisfies(draft -> {
                    assertThat(draft.draftId()).isEqualTo(100L);
                    assertThat(draft.participantPetIds()).containsExactly(11L, 22L);
                });
        verify(participantRepository, times(2)).save(any(CardDraftParticipant.class));

        CardDraft stored = firstStoredDraft();
        when(draftRepository.findByRequestIdOrderByCandidateIndexAsc("request-1"))
                .thenReturn(List.of(stored));
        when(participantRepository.findByCardDraftIdOrderByIdAsc(100L)).thenReturn(List.of(
                new CardDraftParticipant(100L, 11L), new CardDraftParticipant(100L, 22L)));

        service.persist(event);

        verify(draftRepository).saveAndFlush(any(CardDraft.class));
    }

    @Test
    void invalidCandidateProducesEmptyCompletionForRequester() {
        ChatRoom room = ChatRoom.openChat("산책", null, 11L, 10, true);
        when(roomParticipantRepository.findByRoomId(7L)).thenReturn(List.of(
                ChatRoomParticipant.join(room, 11L), ChatRoomParticipant.join(room, 22L)));
        when(draftRepository.findByRequestIdOrderByCandidateIndexAsc("request-1"))
                .thenReturn(List.of());

        OpenChatDraftAiResultEvent event = new OpenChatDraftAiResultEvent(
                "request-1", 7L, 101L, 11L,
                OpenChatDraftAiResultEvent.Status.COMPLETED, null,
                List.of(new OpenChatDraftAiResultEvent.Draft(
                        0, "WALK", "2026-08-30", "15:00", "공원", List.of(999L))));

        assertThat(service.persist(event)).containsEntry(101L, List.of());
        verify(draftRepository, never()).saveAndFlush(any());
    }

    private OpenChatDraftAiResultEvent event() {
        return new OpenChatDraftAiResultEvent(
                "request-1", 7L, 101L, 11L,
                OpenChatDraftAiResultEvent.Status.COMPLETED, null,
                List.of(new OpenChatDraftAiResultEvent.Draft(
                        0, "WALK", "2026-08-30", "15:00", "공원",
                        List.of(11L, 22L, 999L))));
    }

    private CardDraft firstStoredDraft() {
        CardDraft draft = new CardDraft(
                7L, 11L, itda.meetingcard.domain.MeetingCardType.WALK, "공원",
                java.time.Instant.parse("2026-08-30T06:00:00Z"),
                "2026-08-30", "15:00", null, "request-1", 0);
        ReflectionTestUtils.setField(draft, "id", 100L);
        return draft;
    }

    private PetRepository.PetOwnerRow owner(long petId, long ownerUserId) {
        PetRepository.PetOwnerRow row = mock(PetRepository.PetOwnerRow.class);
        when(row.getPetId()).thenReturn(petId);
        when(row.getOwnerUserId()).thenReturn(ownerUserId);
        return row;
    }
}
