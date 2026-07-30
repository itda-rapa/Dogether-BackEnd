package itda.meetingcard.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.chat.domain.ChatRoom;
import itda.chat.repository.ChatMessageRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.chat.service.ChatMessageService;
import itda.chat.service.ChatQueryService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.interaction.dto.InteractionPairContext;
import itda.interaction.dto.LockedPetContext;
import itda.interaction.dto.LockedUserContext;
import itda.interaction.service.InteractionPairLockService;
import itda.meetingcard.ai.MeetingDraftAiClient;
import itda.meetingcard.domain.MeetingCardType;
import itda.meetingcard.dto.MeetingCardCreateRequest;
import itda.meetingcard.repository.CardDraftRepository;
import itda.meetingcard.repository.MeetingCardRepository;
import itda.meetingcard.repository.MeetingParticipantRepository;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.user.domain.AccountStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MeetingCardPolicyTest {

    private static final long USER_1 = 1L;
    private static final long USER_2 = 2L;
    private static final long PET_1 = 11L;
    private static final long PET_2 = 22L;
    private static final long ROOM_ID = 100L;
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Mock
    private ActivePetQueryService activePetQueryService;
    @Mock
    private ChatQueryService chatQueryService;
    @Mock
    private ChatMessageRepository chatMessageRepository;
    @Mock
    private CardDraftTransactionService cardDraftTransactionService;
    @Mock
    private MeetingDraftAiClient aiClient;
    @Mock
    private ChatRoomRepository chatRoomRepository;
    @Mock
    private ChatMessageService chatMessageService;
    @Mock
    private MeetingCardRepository meetingCardRepository;
    @Mock
    private MeetingParticipantRepository meetingParticipantRepository;
    @Mock
    private CardDraftRepository cardDraftRepository;
    @Mock
    private InteractionPairLockService interactionPairLockService;

    private ActivePetContext actor;

    @BeforeEach
    void setUp() {
        actor = new ActivePetContext(PET_1, USER_1, "pet#0011", "펫1", null, false);
        when(activePetQueryService.requireActivePet(USER_1)).thenReturn(actor);
    }

    @Test
    void draftIsRejectedBeforeAiWhenGreetingReplyIsPending() {
        CardDraftService service = new CardDraftService(
                activePetQueryService,
                chatQueryService,
                chatMessageRepository,
                cardDraftTransactionService,
                aiClient,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        doThrow(new BusinessException(ErrorCode.GREETING_REPLY_REQUIRED))
                .when(chatQueryService)
                .requireGreetingReplyCompleted(ROOM_ID);

        assertThatThrownBy(() -> service.createDraft(USER_1, ROOM_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.GREETING_REPLY_REQUIRED);

        verify(aiClient, never()).extract(org.mockito.ArgumentMatchers.any());
        verify(cardDraftTransactionService, never())
                .save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void confirmLocksPairThenRechecksBlockAndGreetingBeforeWriting() {
        ChatRoom room = org.mockito.Mockito.mock(ChatRoom.class);
        when(room.getPetLowId()).thenReturn(PET_1);
        when(room.getPetHighId()).thenReturn(PET_2);
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(interactionPairLockService.lockInteractionPair(PET_1, PET_2))
                .thenReturn(lockedPair());
        doThrow(new BusinessException(ErrorCode.GREETING_REPLY_REQUIRED))
                .when(chatQueryService)
                .requireGreetingReplyCompleted(ROOM_ID);

        MeetingCardService service = new MeetingCardService(
                activePetQueryService,
                chatQueryService,
                chatRoomRepository,
                chatMessageService,
                meetingCardRepository,
                meetingParticipantRepository,
                cardDraftRepository,
                interactionPairLockService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        MeetingCardCreateRequest request = new MeetingCardCreateRequest(
                ROOM_ID,
                null,
                MeetingCardType.WALK,
                "서울숲",
                NOW.plusSeconds(3600)
        );

        assertThatThrownBy(() -> service.confirm(USER_1, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.GREETING_REPLY_REQUIRED);

        InOrder order = inOrder(
                chatQueryService,
                chatRoomRepository,
                interactionPairLockService
        );
        order.verify(chatQueryService).requireParticipant(ROOM_ID, PET_1);
        order.verify(chatRoomRepository).findById(ROOM_ID);
        order.verify(interactionPairLockService).lockInteractionPair(PET_1, PET_2);
        order.verify(chatQueryService).requireParticipant(ROOM_ID, PET_1);
        order.verify(chatQueryService).requireGreetingReplyCompleted(ROOM_ID);

        verify(meetingCardRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(chatMessageService, never()).postCard(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    private InteractionPairContext lockedPair() {
        return new InteractionPairContext(
                new LockedUserContext(USER_1, AccountStatus.ACTIVE, PET_1, "user#0001"),
                new LockedUserContext(USER_2, AccountStatus.ACTIVE, PET_2, "user#0002"),
                new LockedPetContext(PET_1, USER_1, PetStatus.ACTIVE, null),
                new LockedPetContext(PET_2, USER_2, PetStatus.ACTIVE, null)
        );
    }
}
