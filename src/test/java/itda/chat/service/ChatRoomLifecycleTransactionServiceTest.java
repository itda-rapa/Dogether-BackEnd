package itda.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.chat.domain.ChatRoom;
import itda.chat.domain.RoomStatus;
import itda.chat.domain.RoomType;
import itda.chat.repository.ChatMessageRepository;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.friend.repository.FriendshipRepository;
import itda.greeting.domain.Greeting;
import itda.greeting.domain.GreetingStatus;
import itda.greeting.repository.GreetingRepository;
import itda.interaction.service.InteractionPairLockService;
import itda.meetingcard.repository.CardDraftRepository;
import itda.meetingcard.repository.MeetingCardRepository;
import itda.meetingcard.repository.MeetingParticipantRepository;
import itda.pet.domain.Pet;
import itda.report.repository.ReportRepository;
import itda.risk.contract.RiskSignalType;
import itda.risk.contract.RiskSourceEventCommand;
import itda.risk.contract.RiskSourceEventPublisher;
import itda.risk.contract.RiskSourceType;
import itda.user.domain.User;
import java.time.Instant;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.junit.jupiter.api.Test;

class ChatRoomLifecycleTransactionServiceTest {

    private static final long ROOM_ID = 10L;
    private static final long GREETING_ID = 20L;
    private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");
    private static final Instant CUTOFF = NOW.minusSeconds(30L * 24 * 60 * 60);

    private final GreetingRepository greetingRepository = mock(GreetingRepository.class);
    private final ChatRoomRepository chatRoomRepository = mock(ChatRoomRepository.class);
    private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
    private final ChatRoomParticipantRepository participantRepository =
            mock(ChatRoomParticipantRepository.class);
    private final CardDraftRepository cardDraftRepository = mock(CardDraftRepository.class);
    private final MeetingCardRepository meetingCardRepository = mock(MeetingCardRepository.class);
    private final MeetingParticipantRepository meetingParticipantRepository =
            mock(MeetingParticipantRepository.class);
    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final FriendshipRepository friendshipRepository = mock(FriendshipRepository.class);
    private final InteractionPairLockService interactionPairLockService =
            mock(InteractionPairLockService.class);
    private final RiskSourceEventPublisher riskSourceEventPublisher =
            mock(RiskSourceEventPublisher.class);

    private final ChatRoomLifecycleTransactionService service =
            new ChatRoomLifecycleTransactionService(
                    greetingRepository,
                    chatRoomRepository,
                    chatMessageRepository,
                    participantRepository,
                    cardDraftRepository,
                    meetingCardRepository,
                    meetingParticipantRepository,
                    reportRepository,
                    friendshipRepository,
                    interactionPairLockService,
                    riskSourceEventPublisher
            );

    @Test
    void expiresAndDeletesUnreportedRoom() {
        Greeting greeting = mock(Greeting.class);
        ChatRoom room = mock(ChatRoom.class);
        when(greetingRepository.findByIdForUpdate(GREETING_ID))
                .thenReturn(Optional.of(greeting));
        when(greeting.getStatus()).thenReturn(GreetingStatus.SENT);
        stubGreetingRiskFields(greeting);
        when(greeting.getExpiresAt()).thenReturn(NOW.minusSeconds(1));
        when(greeting.getRoomId()).thenReturn(ROOM_ID);
        when(chatRoomRepository.findByIdForUpdate(ROOM_ID))
                .thenReturn(Optional.of(room));
        when(room.getId()).thenReturn(ROOM_ID);
        when(room.getPetLowId()).thenReturn(11L);
        when(room.getPetHighId()).thenReturn(22L);
        when(friendshipRepository.existsByPetLowIdAndPetHighId(11L, 22L))
                .thenReturn(false);
        when(reportRepository.existsByRoomId(ROOM_ID)).thenReturn(false);
        when(greetingRepository.existsByRoomIdAndStatus(
                ROOM_ID, GreetingStatus.RESPONDED)).thenReturn(false);
        when(greetingRepository.findSentByRoomIdForUpdate(ROOM_ID))
                .thenReturn(java.util.List.of(greeting));

        ChatRoomLifecycleTransactionService.ExpiredGreetingOutcome result =
        service.expireGreetingAndCleanup(GREETING_ID, ROOM_ID, 11L, 22L, NOW);

        assertThat(result.expired()).isTrue();
        assertThat(result.roomDeleted()).isTrue();
        verify(greeting).expire();
        ArgumentCaptor<RiskSourceEventCommand> eventCaptor =
                ArgumentCaptor.forClass(RiskSourceEventCommand.class);
        verify(riskSourceEventPublisher).enqueue(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isEqualTo(new RiskSourceEventCommand(
                RiskSourceType.GREETING,
                GREETING_ID,
                RiskSignalType.GREETING_EXPIRED,
                11L,
                22L,
                NOW.minusSeconds(1),
                java.util.Map.of()
        ));
        verify(chatMessageRepository).deleteByRoomId(ROOM_ID);
        verify(meetingParticipantRepository).deleteByRoomId(ROOM_ID);
        verify(meetingCardRepository).deleteByRoomId(ROOM_ID);
        verify(cardDraftRepository).deleteByRoomId(ROOM_ID);
        verify(participantRepository).deleteByRoomId(ROOM_ID);
        verify(chatRoomRepository).delete(room);
        verify(chatRoomRepository).flush();
    }

    @Test
    void expiresButPreservesReportedRoom() {
        Greeting greeting = mock(Greeting.class);
        ChatRoom room = mock(ChatRoom.class);
        when(greetingRepository.findByIdForUpdate(GREETING_ID))
                .thenReturn(Optional.of(greeting));
        when(greeting.getStatus()).thenReturn(GreetingStatus.SENT);
        stubGreetingRiskFields(greeting);
        when(greeting.getExpiresAt()).thenReturn(NOW.minusSeconds(1));
        when(greeting.getRoomId()).thenReturn(ROOM_ID);
        when(chatRoomRepository.findByIdForUpdate(ROOM_ID))
                .thenReturn(Optional.of(room));
        when(room.getId()).thenReturn(ROOM_ID);
        when(room.getPetLowId()).thenReturn(11L);
        when(room.getPetHighId()).thenReturn(22L);
        when(reportRepository.existsByRoomId(ROOM_ID)).thenReturn(true);
        when(greetingRepository.findSentByRoomIdForUpdate(ROOM_ID))
                .thenReturn(java.util.List.of(greeting));

        ChatRoomLifecycleTransactionService.ExpiredGreetingOutcome result =
        service.expireGreetingAndCleanup(GREETING_ID, ROOM_ID, 11L, 22L, NOW);

        assertThat(result.expired()).isTrue();
        assertThat(result.roomDeleted()).isFalse();
        verify(greeting).expire();
        verify(chatMessageRepository, never()).deleteByRoomId(ROOM_ID);
        verify(participantRepository, never()).deleteByRoomId(ROOM_ID);
        verify(chatRoomRepository, never()).delete(room);
    }

    @Test
    void expiresGreetingButPreservesFriendRoom() {
        Greeting greeting = mock(Greeting.class);
        ChatRoom room = mock(ChatRoom.class);
        when(greetingRepository.findByIdForUpdate(GREETING_ID))
                .thenReturn(Optional.of(greeting));
        when(greeting.getStatus()).thenReturn(GreetingStatus.SENT);
        stubGreetingRiskFields(greeting);
        when(greeting.getExpiresAt()).thenReturn(NOW.minusSeconds(1));
        when(greeting.getRoomId()).thenReturn(ROOM_ID);
        when(chatRoomRepository.findByIdForUpdate(ROOM_ID))
                .thenReturn(Optional.of(room));
        when(room.getId()).thenReturn(ROOM_ID);
        when(room.getPetLowId()).thenReturn(11L);
        when(room.getPetHighId()).thenReturn(22L);
        when(reportRepository.existsByRoomId(ROOM_ID)).thenReturn(false);
        when(greetingRepository.existsByRoomIdAndStatus(
                ROOM_ID, GreetingStatus.RESPONDED)).thenReturn(false);
        when(greetingRepository.findSentByRoomIdForUpdate(ROOM_ID))
                .thenReturn(java.util.List.of(greeting));
        when(friendshipRepository.existsByPetLowIdAndPetHighId(11L, 22L))
                .thenReturn(true);

        ChatRoomLifecycleTransactionService.ExpiredGreetingOutcome result =
                service.expireGreetingAndCleanup(GREETING_ID, ROOM_ID, 11L, 22L, NOW);

        assertThat(result.expired()).isTrue();
        assertThat(result.roomDeleted()).isFalse();
        verify(greeting).expire();
        verify(chatMessageRepository, never()).deleteByRoomId(ROOM_ID);
        verify(chatRoomRepository, never()).delete(room);

        InOrder lockOrder = inOrder(interactionPairLockService, chatRoomRepository);
        lockOrder.verify(interactionPairLockService)
                .lockInteractionPair(11L, 22L);
        lockOrder.verify(chatRoomRepository).findByIdForUpdate(ROOM_ID);
    }

    @Test
    void doesNotPublishForAlreadyExpiredGreeting() {
        Greeting greeting = mock(Greeting.class);
        when(greetingRepository.findByIdForUpdate(GREETING_ID))
                .thenReturn(Optional.of(greeting));
        when(greeting.getStatus()).thenReturn(GreetingStatus.EXPIRED);
        when(greeting.getRoomId()).thenReturn(ROOM_ID);
        when(chatRoomRepository.findByIdForUpdate(ROOM_ID))
                .thenReturn(Optional.empty());

        ChatRoomLifecycleTransactionService.ExpiredGreetingOutcome result =
                service.expireGreetingAndCleanup(GREETING_ID, ROOM_ID, null, null, NOW);

        assertThat(result.expired()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(riskSourceEventPublisher);
        verify(greeting, never()).expire();
    }

    private void stubGreetingRiskFields(Greeting greeting) {
        Pet fromPet = mock(Pet.class);
        Pet toPet = mock(Pet.class);
        User fromUser = mock(User.class);
        User toUser = mock(User.class);
        when(greeting.getId()).thenReturn(GREETING_ID);
        when(greeting.getFromPet()).thenReturn(fromPet);
        when(greeting.getToPet()).thenReturn(toPet);
        when(fromPet.getOwner()).thenReturn(fromUser);
        when(toPet.getOwner()).thenReturn(toUser);
        when(fromUser.getId()).thenReturn(11L);
        when(toUser.getId()).thenReturn(22L);
    }

    @Test
    void archivesOnlyAnOldAnsweredNonFriendRoom() {
        ChatRoom room = mock(ChatRoom.class);
        when(chatRoomRepository.findByIdForUpdate(ROOM_ID))
                .thenReturn(Optional.of(room));
        when(room.getType()).thenReturn(RoomType.DIRECT);
        when(room.getStatus()).thenReturn(RoomStatus.ACTIVE);
        when(room.getLastMessageAt()).thenReturn(CUTOFF.minusSeconds(1));
        when(room.getPetLowId()).thenReturn(11L);
        when(room.getPetHighId()).thenReturn(22L);
        when(greetingRepository.existsByRoomIdAndStatus(
                ROOM_ID, GreetingStatus.RESPONDED)).thenReturn(true);
        when(friendshipRepository.existsByPetLowIdAndPetHighId(11L, 22L))
                .thenReturn(false);

        assertThat(service.archiveIfEligible(ROOM_ID, 11L, 22L, NOW, CUTOFF)).isTrue();

        verify(room).archive(NOW);
    }

    @Test
    void doesNotArchiveFriendRoom() {
        ChatRoom room = mock(ChatRoom.class);
        when(chatRoomRepository.findByIdForUpdate(ROOM_ID))
                .thenReturn(Optional.of(room));
        when(room.getType()).thenReturn(RoomType.DIRECT);
        when(room.getStatus()).thenReturn(RoomStatus.ACTIVE);
        when(room.getLastMessageAt()).thenReturn(CUTOFF.minusSeconds(1));
        when(room.getPetLowId()).thenReturn(11L);
        when(room.getPetHighId()).thenReturn(22L);
        when(greetingRepository.existsByRoomIdAndStatus(
                ROOM_ID, GreetingStatus.RESPONDED)).thenReturn(true);
        when(friendshipRepository.existsByPetLowIdAndPetHighId(11L, 22L))
                .thenReturn(true);

        assertThat(service.archiveIfEligible(ROOM_ID, 11L, 22L, NOW, CUTOFF)).isFalse();

        verify(room, never()).archive(NOW);
    }
}
