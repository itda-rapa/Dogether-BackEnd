package itda.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.chat.domain.ChatMessage;
import itda.chat.domain.ChatRoom;
import itda.chat.domain.MessageType;
import itda.chat.domain.RoomType;
import itda.chat.domain.SenderType;
import itda.chat.dto.ChatMessageCreateRequest;
import itda.chat.dto.ChatMessageResult;
import itda.chat.repository.ChatMessageRepository;
import itda.chat.repository.ChatMessageRepository.MessageUpsert;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.greeting.domain.Greeting;
import itda.greeting.domain.GreetingStatus;
import itda.greeting.repository.GreetingRepository;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Mocks are always built before the {@code when(...)} call that returns them — creating a mock
 * inside a {@code thenReturn(...)} argument nests stubbing and trips UnfinishedStubbingException.
 */
@ExtendWith(MockitoExtension.class)
class ChatMessageServiceTest {

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private ChatRoomParticipantRepository participantRepository;

    @Mock
    private GreetingRepository greetingRepository;

    @Mock
    private PetRepository petRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ChatMessageService chatMessageService;

    @BeforeEach
    void setUp() {
        chatMessageService = new ChatMessageService(
                chatMessageRepository,
                chatRoomRepository,
                participantRepository,
                greetingRepository,
                petRepository,
                eventPublisher
        );
    }

    // ---------- request validation ----------

    @Test
    void textSendRequiresClientMessageId() {
        assertThatThrownBy(() -> chatMessageService.sendText(1L, 10L, request(null, "hello")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_CLIENT_MESSAGE_ID_REQUIRED);
    }

    @Test
    void textSendRequiresBody() {
        assertThatThrownBy(() -> chatMessageService.sendText(1L, 10L, request("idem-1", "  ")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void systemNoticeRejectsBlankBody() {
        // postSystem is an internal entry point and never sees bean validation, so the service
        // must reject this itself rather than letting ck_chat_message_payload raise a raw
        // persistence exception.
        assertThatThrownBy(() -> chatMessageService.postSystem(1L, "   ", null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void senderMustBeARoomParticipant() {
        ChatRoom room = mock(ChatRoom.class);

        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "idem-1"))
                .thenReturn(Optional.empty());
        when(chatRoomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(room));
        when(participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(1L, 999L)).thenReturn(false);

        assertThatThrownBy(() -> chatMessageService.sendText(1L, 999L, request("idem-1", "hello")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_SENDER_NOT_PARTICIPANT);
    }

    @Test
    void sendingToAMissingRoomIsRejected() {
        when(chatMessageRepository.findByRoomIdAndClientMessageId(99L, "idem-9"))
                .thenReturn(Optional.empty());
        when(chatRoomRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatMessageService.sendText(99L, 10L, request("idem-9", "hi")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
    }

    // ---------- created flag and room activity ----------

    @Test
    void newTextIsCreatedAndAdvancesRoomActivity() {
        ChatRoom room = mock(ChatRoom.class);
        ChatMessage stored = textMsg(2L, 10L, "hello", "idem-2");
        MessageUpsert upsert = upsert(2L, true);

        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "idem-2"))
                .thenReturn(Optional.empty());
        when(chatRoomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(room));
        when(participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(1L, 10L)).thenReturn(true);
        when(chatMessageRepository.insertMessageOnConflictWithReturning(
                1L, "PET", 10L, "TEXT", "hello", null, "idem-2"))
                .thenReturn(upsert);
        when(chatMessageRepository.findById(2L)).thenReturn(Optional.of(stored));
        Pet sender = mock(Pet.class);
        when(sender.getNickname()).thenReturn("Mong");
        when(petRepository.findById(10L)).thenReturn(Optional.of(sender));

        ChatMessageResult result = chatMessageService.sendText(1L, 10L, request("idem-2", "hello"));

        assertThat(result.created()).isTrue();
        assertThat(result.message().getId()).isEqualTo(2L);
        verify(chatRoomRepository).activateAndTouchLastMessageAt(1L);
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.<Object>argThat(event ->
                event instanceof itda.chat.event.ChatMessageCommittedEvent committed
                        && "Mong".equals(committed.message().senderPetNickname())));
    }

    @Test
    void sequentialRetryReturnsTheOriginalWithoutReinserting() {
        ChatMessage original = textMsg(1L, 10L, "hello", "idem-1");

        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "idem-1"))
                .thenReturn(Optional.of(original));

        ChatMessageResult result = chatMessageService.sendText(1L, 10L, request("idem-1", "hello"));

        assertThat(result.created()).isFalse();
        assertThat(result.message().getId()).isEqualTo(1L);
        verify(chatMessageRepository, never()).insertMessageOnConflictWithReturning(
                anyLong(), anyString(), any(), anyString(), any(), any(), any());
        verify(chatRoomRepository, never()).activateAndTouchLastMessageAt(anyLong());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void concurrentRetryLosesTheRaceAndLeavesRoomActivityAlone() {
        // This caller passed the fast-path lookup before the winner committed, so it reaches the
        // upsert and gets the winner's row back. It must behave like the sequential retry above:
        // not created, and no activity bump. Reporting it as created would also answer 201 to a
        // duplicate send.
        ChatRoom room = mock(ChatRoom.class);
        ChatMessage winner = textMsg(2L, 10L, "hello", "idem-2");
        MessageUpsert upsert = upsert(2L, false);

        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "idem-2"))
                .thenReturn(Optional.empty());
        when(chatRoomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(room));
        when(participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(1L, 10L)).thenReturn(true);
        when(chatMessageRepository.insertMessageOnConflictWithReturning(
                1L, "PET", 10L, "TEXT", "hello", null, "idem-2"))
                .thenReturn(upsert);
        when(chatMessageRepository.findById(2L)).thenReturn(Optional.of(winner));

        ChatMessageResult result = chatMessageService.sendText(1L, 10L, request("idem-2", "hello"));

        assertThat(result.created()).isFalse();
        assertThat(result.message().getId()).isEqualTo(2L);
        verify(chatRoomRepository, never()).activateAndTouchLastMessageAt(anyLong());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ---------- idempotency key misuse ----------

    @Test
    void reusingKeyWithDifferentBodyIsRejected() {
        ChatMessage original = textMsg(1L, 10L, "original-body", "idem-1");

        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "idem-1"))
                .thenReturn(Optional.of(original));

        assertThatThrownBy(() ->
                chatMessageService.sendText(1L, 10L, request("idem-1", "different-body")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_DUPLICATE_MESSAGE);
    }

    @Test
    void reusingAnotherPetsKeyIsRejected() {
        // Same room, same key, same body — but pet 20 did not author the stored message.
        ChatMessage original = textMsg(1L, 10L, "hello", "idem-1");

        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "idem-1"))
                .thenReturn(Optional.of(original));

        assertThatThrownBy(() -> chatMessageService.sendText(1L, 20L, request("idem-1", "hello")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_DUPLICATE_MESSAGE);
    }

    @Test
    void greetingSenderCannotSendAgainBeforeReply() {
        when(greetingRepository
                .findFirstByRoomIdAndToPet_IdAndStatusOrderByIdAsc(
                        1L,
                        10L,
                        GreetingStatus.SENT
                )).thenReturn(Optional.empty());
        when(greetingRepository.existsByRoomIdAndFromPet_IdAndStatus(
                1L,
                10L,
                GreetingStatus.SENT
        )).thenReturn(true);

        assertThatThrownBy(() ->
                chatMessageService.sendText(
                        1L,
                        10L,
                        request("idem-3", "한 번 더 보낼게요")
                ))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.GREETING_REPLY_REQUIRED);

        verify(chatMessageRepository, never())
                .insertMessageOnConflictWithReturning(
                        anyLong(),
                        anyString(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any()
                );
    }

    @Test
    void firstReplyMarksGreetingResponded() {
        Greeting greeting = mock(Greeting.class);
        ChatRoom room = mock(ChatRoom.class);
        ChatMessage stored = textMsg(3L, 20L, "반가워요", "reply-1");
        MessageUpsert upsert = upsert(3L, true);
        when(greetingRepository
                .findFirstByRoomIdAndToPet_IdAndStatusOrderByIdAsc(
                        1L,
                        20L,
                        GreetingStatus.SENT
                )).thenReturn(Optional.of(greeting));
        when(chatMessageRepository.findByRoomIdAndClientMessageId(
                1L,
                "reply-1"
        )).thenReturn(Optional.empty());
        when(chatRoomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(room));
        when(participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(1L, 20L))
                .thenReturn(true);
        when(chatMessageRepository.insertMessageOnConflictWithReturning(
                1L,
                "PET",
                20L,
                "TEXT",
                "반가워요",
                null,
                "reply-1"
        )).thenReturn(upsert);
        when(chatMessageRepository.findById(3L))
                .thenReturn(Optional.of(stored));

        ChatMessageResult result = chatMessageService.sendText(
                1L,
                20L,
                request("reply-1", "반가워요")
        );

        assertThat(result.created()).isTrue();
        verify(greeting).markResponded(
                org.mockito.ArgumentMatchers.any(Instant.class)
        );
    }

    // ---------- server-authored CARD / SYSTEM ----------

    @Test
    void systemNoticeNeedsNoIdempotencyKeyOrParticipation() {
        ChatRoom room = mock(ChatRoom.class);
        ChatMessage stored = systemMsg(100L, "System notice");
        MessageUpsert upsert = upsert(100L, true);

        when(chatRoomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(room));
        when(chatMessageRepository.insertMessageOnConflictWithReturning(
                1L, "SYSTEM", null, "SYSTEM", "System notice", null, null))
                .thenReturn(upsert);
        when(chatMessageRepository.findById(100L)).thenReturn(Optional.of(stored));

        ChatMessageResult result = chatMessageService.postSystem(1L, "System notice", null);

        assertThat(result.message().getType()).isEqualTo(MessageType.SYSTEM);
        assertThat(result.message().getSenderType()).isEqualTo(SenderType.SYSTEM);
        assertThat(result.message().getSenderPetId()).isNull();
        // Without a key there is nothing to look up, and a system notice has no sending Pet to check.
        verify(chatMessageRepository, never()).findByRoomIdAndClientMessageId(any(), any());
        verify(participantRepository, never())
                .existsByRoomIdAndPetIdAndLeftAtIsNull(anyLong(), anyLong());
    }

    @Test
    void cardAnnouncementCarriesMeetingCardId() {
        ChatRoom room = mock(ChatRoom.class);
        ChatMessage stored = cardMsg(50L, 10L, 7L, "card-7");
        MessageUpsert upsert = upsert(50L, true);

        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "card-7"))
                .thenReturn(Optional.empty());
        when(chatRoomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(room));
        when(participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(1L, 10L)).thenReturn(true);
        when(chatMessageRepository.insertMessageOnConflictWithReturning(
                1L, "PET", 10L, "CARD", null, 7L, "card-7"))
                .thenReturn(upsert);
        when(chatMessageRepository.findById(50L)).thenReturn(Optional.of(stored));

        ChatMessageResult result = chatMessageService.postCard(1L, 10L, 7L, "card-7");

        assertThat(result.message().getType()).isEqualTo(MessageType.CARD);
        assertThat(result.message().getMeetingCardId()).isEqualTo(7L);
    }

    // ---------- helpers ----------

    private static ChatMessageCreateRequest request(String clientMessageId, String body) {
        return new ChatMessageCreateRequest(clientMessageId, body);
    }

    private static MessageUpsert upsert(long id, boolean created) {
        MessageUpsert result = mock(MessageUpsert.class);
        lenient().when(result.getId()).thenReturn(id);
        lenient().when(result.getCreated()).thenReturn(created);
        return result;
    }

    private static ChatMessage textMsg(long id, Long senderPetId, String body, String clientMessageId) {
        return mockMsg(id, SenderType.PET, senderPetId, MessageType.TEXT, body, null, clientMessageId);
    }

    private static ChatMessage systemMsg(long id, String body) {
        return mockMsg(id, SenderType.SYSTEM, null, MessageType.SYSTEM, body, null, null);
    }

    private static ChatMessage cardMsg(long id, Long creatorPetId, Long meetingCardId, String clientMessageId) {
        return mockMsg(id, SenderType.PET, creatorPetId, MessageType.CARD, null, meetingCardId, clientMessageId);
    }

    /**
     * Each test drives a different service path, so only a subset of these getters is read.
     * Stub leniently so unused ones do not trip strict-stub checking.
     */
    private static ChatMessage mockMsg(long id, SenderType senderType, Long senderPetId, MessageType type,
                                       String body, Long meetingCardId, String clientMessageId) {
        ChatMessage msg = mock(ChatMessage.class);
        ChatRoom room = mock(ChatRoom.class);
        lenient().when(room.getId()).thenReturn(1L);
        lenient().when(room.getType()).thenReturn(RoomType.DIRECT);
        lenient().when(msg.getRoom()).thenReturn(room);
        lenient().when(msg.getId()).thenReturn(id);
        lenient().when(msg.getSenderType()).thenReturn(senderType);
        lenient().when(msg.getSenderPetId()).thenReturn(senderPetId);
        lenient().when(msg.getType()).thenReturn(type);
        lenient().when(msg.getBody()).thenReturn(body);
        lenient().when(msg.getMeetingCardId()).thenReturn(meetingCardId);
        lenient().when(msg.getClientMessageId()).thenReturn(clientMessageId);
        return msg;
    }
}
