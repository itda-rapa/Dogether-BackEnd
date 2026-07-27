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
import itda.chat.domain.SenderType;
import itda.chat.dto.SendTextRequest;
import itda.chat.repository.ChatMessageRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private ChatMessageService chatMessageService;

    @BeforeEach
    void setUp() {
        chatMessageService = new ChatMessageService(chatMessageRepository, chatRoomRepository);
    }

    // ---------- user-authored TEXT ----------

    @Test
    void textSendRequiresClientMessageId() {
        assertThatThrownBy(() -> chatMessageService.sendText(
                new SendTextRequest(1L, 10L, "hello", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_CLIENT_MESSAGE_ID_REQUIRED);
    }

    @Test
    void textSendRequiresSenderPetId() {
        assertThatThrownBy(() -> chatMessageService.sendText(
                new SendTextRequest(1L, null, "hello", "idem-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_SENDER_REQUIRED);
    }

    @Test
    void newTextIsInsertedAndAdvancesRoomActivity() {
        ChatRoom room = mock(ChatRoom.class);
        ChatMessage stored = textMsg(2L, 10L, "hello", "idem-2");

        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "idem-2"))
                .thenReturn(Optional.empty());
        when(chatRoomRepository.findById(1L)).thenReturn(Optional.of(room));
        when(chatMessageRepository.insertMessageOnConflictWithReturning(
                1L, "PET", 10L, "TEXT", "hello", null, "idem-2"))
                .thenReturn(2L);
        when(chatMessageRepository.findById(2L)).thenReturn(Optional.of(stored));

        ChatMessage result = chatMessageService.sendText(
                new SendTextRequest(1L, 10L, "hello", "idem-2"));

        assertThat(result.getId()).isEqualTo(2L);
        verify(chatRoomRepository).touchLastMessageAt(1L);
    }

    @Test
    void retriedTextReturnsTheOriginalWithoutReinserting() {
        ChatMessage original = textMsg(1L, 10L, "hello", "idem-1");

        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "idem-1"))
                .thenReturn(Optional.of(original));

        ChatMessage result = chatMessageService.sendText(
                new SendTextRequest(1L, 10L, "hello", "idem-1"));

        assertThat(result.getId()).isEqualTo(1L);
        verify(chatMessageRepository, never()).insertMessageOnConflictWithReturning(
                anyLong(), anyString(), any(), anyString(), any(), any(), any());
        // A retry is not new room activity, so the room timestamp must not move.
        verify(chatRoomRepository, never()).touchLastMessageAt(anyLong());
    }

    @Test
    void reusingKeyWithDifferentBodyIsRejected() {
        ChatMessage original = textMsg(1L, 10L, "original-body", "idem-1");

        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "idem-1"))
                .thenReturn(Optional.of(original));

        assertThatThrownBy(() -> chatMessageService.sendText(
                new SendTextRequest(1L, 10L, "different-body", "idem-1")))
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

        assertThatThrownBy(() -> chatMessageService.sendText(
                new SendTextRequest(1L, 20L, "hello", "idem-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_DUPLICATE_MESSAGE);
    }

    @Test
    void sendingToAMissingRoomIsRejected() {
        when(chatMessageRepository.findByRoomIdAndClientMessageId(99L, "idem-9"))
                .thenReturn(Optional.empty());
        when(chatRoomRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatMessageService.sendText(
                new SendTextRequest(99L, 10L, "hi", "idem-9")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
    }

    // ---------- server-authored CARD / SYSTEM ----------

    @Test
    void systemNoticeNeedsNoIdempotencyKey() {
        ChatRoom room = mock(ChatRoom.class);
        ChatMessage stored = systemMsg(100L, "System notice");

        when(chatRoomRepository.findById(1L)).thenReturn(Optional.of(room));
        when(chatMessageRepository.insertMessageOnConflictWithReturning(
                1L, "SYSTEM", null, "SYSTEM", "System notice", null, null))
                .thenReturn(100L);
        when(chatMessageRepository.findById(100L)).thenReturn(Optional.of(stored));

        ChatMessage result = chatMessageService.postSystem(1L, "System notice", null);

        assertThat(result.getType()).isEqualTo(MessageType.SYSTEM);
        assertThat(result.getSenderType()).isEqualTo(SenderType.SYSTEM);
        assertThat(result.getSenderPetId()).isNull();
        // Without a key there is nothing to look up.
        verify(chatMessageRepository, never()).findByRoomIdAndClientMessageId(any(), any());
    }

    @Test
    void cardAnnouncementCarriesMeetingCardId() {
        ChatRoom room = mock(ChatRoom.class);
        ChatMessage stored = cardMsg(50L, 10L, 7L, "card-7");

        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "card-7"))
                .thenReturn(Optional.empty());
        when(chatRoomRepository.findById(1L)).thenReturn(Optional.of(room));
        when(chatMessageRepository.insertMessageOnConflictWithReturning(
                1L, "PET", 10L, "CARD", null, 7L, "card-7"))
                .thenReturn(50L);
        when(chatMessageRepository.findById(50L)).thenReturn(Optional.of(stored));

        ChatMessage result = chatMessageService.postCard(1L, 10L, 7L, "card-7");

        assertThat(result.getType()).isEqualTo(MessageType.CARD);
        assertThat(result.getMeetingCardId()).isEqualTo(7L);
    }

    // ---------- helpers ----------

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
