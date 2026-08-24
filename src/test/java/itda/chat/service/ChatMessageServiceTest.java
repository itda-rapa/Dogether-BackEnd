package itda.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.chat.domain.ChatMessage;
import itda.chat.domain.ChatMessageAttachment;
import itda.chat.domain.ChatRoom;
import itda.chat.domain.MessageType;
import itda.chat.domain.RoomType;
import itda.chat.domain.SenderType;
import itda.chat.dto.ChatMessageCreateRequest;
import itda.chat.dto.ChatMessageResult;
import itda.chat.dto.response.ChatMessageResponse;
import itda.chat.repository.ChatMessageAttachmentRepository;
import itda.chat.repository.ChatMessageRepository;
import itda.chat.repository.ChatMessageRepository.MessageUpsert;
import itda.chat.repository.ChatRoomParticipantRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.greeting.domain.Greeting;
import itda.greeting.domain.GreetingStatus;
import itda.greeting.repository.GreetingRepository;
import itda.media.domain.MediaType;
import itda.media.service.MediaService;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import itda.setlog.service.SetlogQueryService;
import java.time.Instant;
import java.sql.SQLException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;

@ExtendWith(MockitoExtension.class)
class ChatMessageServiceTest {

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ChatMessageAttachmentRepository attachmentRepository;

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private ChatRoomParticipantRepository participantRepository;

    @Mock
    private GreetingRepository greetingRepository;

    @Mock
    private PetRepository petRepository;

    @Mock
    private MediaService mediaService;

    @Mock
    private SetlogQueryService setlogQueryService;

    @Mock
    private ChatMessageResponseAssembler responseAssembler;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ChatMessageService chatMessageService;

    @BeforeEach
    void setUp() {
        chatMessageService = new ChatMessageService(
                chatMessageRepository,
                attachmentRepository,
                chatRoomRepository,
                participantRepository,
                greetingRepository,
                petRepository,
                mediaService,
                setlogQueryService,
                responseAssembler,
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
    void everyUserMessageTypeRequiresClientMessageId() {
        for (ChatMessageCreateRequest request : new ChatMessageCreateRequest[]{
                new ChatMessageCreateRequest(null, MessageType.TEXT, "hello", null, null),
                new ChatMessageCreateRequest(null, MessageType.IMAGE, null, 501L, null),
                new ChatMessageCreateRequest(null, MessageType.VIDEO, null, 502L, null),
                new ChatMessageCreateRequest(null, MessageType.SETLOG_SHARE, null, null, 77L)
        }) {
            assertThatThrownBy(() -> chatMessageService.sendMessage(1L, 10L, 7L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.CHAT_CLIENT_MESSAGE_ID_REQUIRED);
        }
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
                1L, "PET", 10L, "TEXT", "hello", null, null, "idem-2"))
                .thenReturn(upsert);
        when(chatMessageRepository.findById(2L)).thenReturn(Optional.of(stored));
        Pet sender = mock(Pet.class);
        when(sender.getNickname()).thenReturn("Mong");
        when(petRepository.findById(10L)).thenReturn(Optional.of(sender));
        when(responseAssembler.toResponse(any(ChatMessage.class), eq("Mong")))
                .thenReturn(responseWithNickname("Mong"));

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
                anyLong(), anyString(), any(), anyString(), any(), any(), any(), any());
        verify(chatRoomRepository, never()).activateAndTouchLastMessageAt(anyLong());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void concurrentRetryLosesTheRaceAndLeavesRoomActivityAlone() {
        ChatRoom room = mock(ChatRoom.class);
        ChatMessage winner = textMsg(2L, 10L, "hello", "idem-2");
        MessageUpsert upsert = upsert(2L, false);

        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "idem-2"))
                .thenReturn(Optional.empty());
        when(chatRoomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(room));
        when(participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(1L, 10L)).thenReturn(true);
        when(chatMessageRepository.insertMessageOnConflictWithReturning(
                1L, "PET", 10L, "TEXT", "hello", null, null, "idem-2"))
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
                        anyLong(), anyString(), any(), anyString(), any(), any(), any(), any());
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

    // ---------- greeting gate applies to every user type ----------

    @Test
    void greetingSenderCannotSendImageBeforeReply() {
        when(greetingRepository.findFirstByRoomIdAndToPet_IdAndStatusOrderByIdAsc(
                1L, 10L, GreetingStatus.SENT)).thenReturn(Optional.empty());
        when(greetingRepository.existsByRoomIdAndFromPet_IdAndStatus(
                1L, 10L, GreetingStatus.SENT)).thenReturn(true);

        assertThatThrownBy(() -> chatMessageService.sendMessage(1L, 10L, 7L,
                new ChatMessageCreateRequest("img-g", MessageType.IMAGE, null, 501L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.GREETING_REPLY_REQUIRED);

        verify(mediaService, never()).requireOwnedPlayableMedia(any(), any(), any());
    }

    @Test
    void greetingSenderCannotSendSetlogShareBeforeReply() {
        when(greetingRepository.findFirstByRoomIdAndToPet_IdAndStatusOrderByIdAsc(
                1L, 10L, GreetingStatus.SENT)).thenReturn(Optional.empty());
        when(greetingRepository.existsByRoomIdAndFromPet_IdAndStatus(
                1L, 10L, GreetingStatus.SENT)).thenReturn(true);

        assertThatThrownBy(() -> chatMessageService.sendMessage(1L, 10L, 7L,
                new ChatMessageCreateRequest("set-g", MessageType.SETLOG_SHARE, null, null, 77L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.GREETING_REPLY_REQUIRED);

        verify(setlogQueryService, never()).requireShareableSetlog(any(), any());
    }

    @Test
    void greetingRecipientImageResponseMarksResponded() {
        Greeting greeting = mock(Greeting.class);
        ChatRoom room = mock(ChatRoom.class);
        ChatMessage stored = mediaMsg(3L, 20L, MessageType.IMAGE, 501L, "reply-img");
        MessageUpsert upsert = upsert(3L, true);
        itda.media.domain.Media media = mock(itda.media.domain.Media.class);

        when(greetingRepository.findFirstByRoomIdAndToPet_IdAndStatusOrderByIdAsc(
                1L, 20L, GreetingStatus.SENT)).thenReturn(Optional.of(greeting));
        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "reply-img"))
                .thenReturn(Optional.empty());
        when(chatRoomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(room));
        when(participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(1L, 20L)).thenReturn(true);
        when(mediaService.requireOwnedPlayableMedia(501L, 7L, MediaType.IMAGE)).thenReturn(media);
        when(chatMessageRepository.insertMessageOnConflictWithReturning(
                1L, "PET", 20L, "IMAGE", null, null, null, "reply-img"))
                .thenReturn(upsert);
        when(chatMessageRepository.findById(3L)).thenReturn(Optional.of(stored));

        ChatMessageResult result = chatMessageService.sendMessage(1L, 20L, 7L,
                new ChatMessageCreateRequest("reply-img", MessageType.IMAGE, null, 501L, null));

        assertThat(result.created()).isTrue();
        verify(greeting).markResponded(org.mockito.ArgumentMatchers.any(Instant.class));
    }

    @Test
    void greetingRecipientSetlogShareResponseMarksResponded() {
        Greeting greeting = mock(Greeting.class);
        ChatRoom room = mock(ChatRoom.class);
        ChatMessage stored = setlogShareMsg(3L, 20L, 77L, "reply-set");
        MessageUpsert upsert = upsert(3L, true);
        itda.setlog.domain.Setlog setlog = mock(itda.setlog.domain.Setlog.class);

        when(greetingRepository.findFirstByRoomIdAndToPet_IdAndStatusOrderByIdAsc(
                1L, 20L, GreetingStatus.SENT)).thenReturn(Optional.of(greeting));
        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "reply-set"))
                .thenReturn(Optional.empty());
        when(chatRoomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(room));
        when(participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(1L, 20L)).thenReturn(true);
        when(setlogQueryService.requireShareableSetlog(77L, 7L)).thenReturn(setlog);
        when(chatMessageRepository.insertMessageOnConflictWithReturning(
                1L, "PET", 20L, "SETLOG_SHARE", null, null, 77L, "reply-set"))
                .thenReturn(upsert);
        when(chatMessageRepository.findById(3L)).thenReturn(Optional.of(stored));

        ChatMessageResult result = chatMessageService.sendMessage(1L, 20L, 7L,
                new ChatMessageCreateRequest("reply-set", MessageType.SETLOG_SHARE, null, null, 77L));

        assertThat(result.created()).isTrue();
        verify(greeting).markResponded(org.mockito.ArgumentMatchers.any(Instant.class));
    }

    // ---------- server-authored CARD / SYSTEM ----------

    @Test
    void systemNoticeNeedsNoIdempotencyKeyOrParticipation() {
        ChatRoom room = mock(ChatRoom.class);
        ChatMessage stored = systemMsg(100L, "System notice");
        MessageUpsert upsert = upsert(100L, true);

        when(chatRoomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(room));
        when(chatMessageRepository.insertMessageOnConflictWithReturning(
                1L, "SYSTEM", null, "SYSTEM", "System notice", null, null, null))
                .thenReturn(upsert);
        when(chatMessageRepository.findById(100L)).thenReturn(Optional.of(stored));

        ChatMessageResult result = chatMessageService.postSystem(1L, "System notice", null);

        assertThat(result.message().getType()).isEqualTo(MessageType.SYSTEM);
        assertThat(result.message().getSenderType()).isEqualTo(SenderType.SYSTEM);
        assertThat(result.message().getSenderPetId()).isNull();
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
                1L, "PET", 10L, "CARD", null, 7L, null, "card-7"))
                .thenReturn(upsert);
        when(chatMessageRepository.findById(50L)).thenReturn(Optional.of(stored));

        ChatMessageResult result = chatMessageService.postCard(1L, 10L, 7L, "card-7");

        assertThat(result.message().getType()).isEqualTo(MessageType.CARD);
        assertThat(result.message().getMeetingCardId()).isEqualTo(7L);
    }

    // ---------- typed message dispatch ----------

    @Test
    void serverOnlyTypesAreRejectedFromUsers() {
        assertThatThrownBy(() -> chatMessageService.sendMessage(1L, 10L, 7L,
                new ChatMessageCreateRequest("idem-c", MessageType.CARD, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MESSAGE_TYPE_INVALID);

        assertThatThrownBy(() -> chatMessageService.sendMessage(1L, 10L, 7L,
                new ChatMessageCreateRequest("idem-s", MessageType.SYSTEM, "notice", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MESSAGE_TYPE_INVALID);
    }

    @Test
    void legacyTextWithoutTypeIsNormalizedToText() {
        ChatRoom room = mock(ChatRoom.class);
        ChatMessage stored = textMsg(2L, 10L, "hello", "legacy-1");
        MessageUpsert upsert = upsert(2L, true);

        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "legacy-1"))
                .thenReturn(Optional.empty());
        when(chatRoomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(room));
        when(participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(1L, 10L)).thenReturn(true);
        when(chatMessageRepository.insertMessageOnConflictWithReturning(
                1L, "PET", 10L, "TEXT", "hello", null, null, "legacy-1"))
                .thenReturn(upsert);
        when(chatMessageRepository.findById(2L)).thenReturn(Optional.of(stored));

        ChatMessageResult result = chatMessageService.sendMessage(1L, 10L, 7L,
                new ChatMessageCreateRequest("legacy-1", null, "hello", null, null));

        assertThat(result.created()).isTrue();
        assertThat(result.message().getType()).isEqualTo(MessageType.TEXT);
    }

    @Test
    void typeMissingWithMediaIdIsRejected() {
        assertThatThrownBy(() -> chatMessageService.sendMessage(1L, 10L, 7L,
                new ChatMessageCreateRequest("legacy-m", null, "hello", 501L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MESSAGE_PAYLOAD_INVALID);
    }

    @Test
    void typeMissingWithSetlogIdIsRejected() {
        assertThatThrownBy(() -> chatMessageService.sendMessage(1L, 10L, 7L,
                new ChatMessageCreateRequest("legacy-s", null, "hello", null, 77L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MESSAGE_PAYLOAD_INVALID);
    }

    @Test
    void imageWithoutMediaIdIsRejected() {
        assertThatThrownBy(() -> chatMessageService.sendMessage(1L, 10L, 7L,
                new ChatMessageCreateRequest("idem-i", MessageType.IMAGE, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MESSAGE_PAYLOAD_INVALID);
    }

    @Test
    void imageWithBodyIsRejected() {
        assertThatThrownBy(() -> chatMessageService.sendMessage(1L, 10L, 7L,
                new ChatMessageCreateRequest("idem-i", MessageType.IMAGE, "caption", 501L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MESSAGE_PAYLOAD_INVALID);
    }

    @Test
    void setlogShareWithoutSetlogIdIsRejected() {
        assertThatThrownBy(() -> chatMessageService.sendMessage(1L, 10L, 7L,
                new ChatMessageCreateRequest("idem-s", MessageType.SETLOG_SHARE, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MESSAGE_PAYLOAD_INVALID);
    }

    @Test
    void imageValidatesMediaThenStoresAttachment() {
        ChatRoom room = mock(ChatRoom.class);
        ChatMessage stored = mediaMsg(2L, 10L, MessageType.IMAGE, 501L, "idem-img");
        MessageUpsert upsert = upsert(2L, true);
        itda.media.domain.Media media = mock(itda.media.domain.Media.class);

        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "idem-img"))
                .thenReturn(Optional.empty());
        when(chatRoomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(room));
        when(participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(1L, 10L)).thenReturn(true);
        when(mediaService.requireOwnedPlayableMedia(501L, 7L, MediaType.IMAGE)).thenReturn(media);
        when(attachmentRepository.existsByMediaId(501L)).thenReturn(false);
        when(chatMessageRepository.insertMessageOnConflictWithReturning(
                1L, "PET", 10L, "IMAGE", null, null, null, "idem-img"))
                .thenReturn(upsert);
        when(chatMessageRepository.findById(2L)).thenReturn(Optional.of(stored));

        ChatMessageResult result = chatMessageService.sendMessage(1L, 10L, 7L,
                new ChatMessageCreateRequest("idem-img", MessageType.IMAGE, null, 501L, null));

        assertThat(result.created()).isTrue();
        verify(mediaService).requireOwnedPlayableMedia(501L, 7L, MediaType.IMAGE);
        verify(attachmentRepository).saveAndFlush(any(ChatMessageAttachment.class));
        verify(chatRoomRepository).activateAndTouchLastMessageAt(1L);
    }

    @Test
    void alreadyAttachedMediaIsRejectedBeforeMessageInsert() {
        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "img-used"))
                .thenReturn(Optional.empty());
        when(chatRoomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(mock(ChatRoom.class)));
        when(participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(1L, 10L)).thenReturn(true);
        when(attachmentRepository.existsByMediaId(501L)).thenReturn(true);

        assertThatThrownBy(() -> chatMessageService.sendMessage(1L, 10L, 7L,
                new ChatMessageCreateRequest("img-used", MessageType.IMAGE, null, 501L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MEDIA_ALREADY_ATTACHED);
        verify(chatMessageRepository, never()).insertMessageOnConflictWithReturning(
                anyLong(), anyString(), any(), anyString(), any(), any(), any(), anyString());
    }

    @Test
    void mediaUniqueConstraintViolationIsMappedToChatConflict() {
        ChatRoom room = mock(ChatRoom.class);
        ChatMessage stored = mediaMsg(2L, 10L, MessageType.IMAGE, 501L, "img-race");
        MessageUpsert upsert = upsert(2L, true);
        DataIntegrityViolationException violation = new DataIntegrityViolationException(
                "duplicate media attachment",
                new ConstraintViolationException(
                        "duplicate media attachment", new SQLException(), "uk_chat_attachment_media"));

        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "img-race"))
                .thenReturn(Optional.empty());
        when(chatRoomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(room));
        when(participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(1L, 10L)).thenReturn(true);
        when(mediaService.requireOwnedPlayableMedia(501L, 7L, MediaType.IMAGE)).thenReturn(mock());
        when(attachmentRepository.existsByMediaId(501L)).thenReturn(false);
        when(chatMessageRepository.insertMessageOnConflictWithReturning(
                1L, "PET", 10L, "IMAGE", null, null, null, "img-race"))
                .thenReturn(upsert);
        when(chatMessageRepository.findById(2L)).thenReturn(Optional.of(stored));
        when(attachmentRepository.saveAndFlush(any(ChatMessageAttachment.class))).thenThrow(violation);

        assertThatThrownBy(() -> chatMessageService.sendMessage(1L, 10L, 7L,
                new ChatMessageCreateRequest("img-race", MessageType.IMAGE, null, 501L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MEDIA_ALREADY_ATTACHED);
        verify(chatRoomRepository, never()).activateAndTouchLastMessageAt(1L);
    }

    @Test
    void setlogShareValidatesSetlogThenStoresSharedSetlogId() {
        ChatRoom room = mock(ChatRoom.class);
        ChatMessage stored = setlogShareMsg(3L, 10L, 77L, "idem-set");
        MessageUpsert upsert = upsert(3L, true);

        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "idem-set"))
                .thenReturn(Optional.empty());
        when(chatRoomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(room));
        when(participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(1L, 10L)).thenReturn(true);
        when(chatMessageRepository.insertMessageOnConflictWithReturning(
                1L, "PET", 10L, "SETLOG_SHARE", null, null, 77L, "idem-set"))
                .thenReturn(upsert);
        when(chatMessageRepository.findById(3L)).thenReturn(Optional.of(stored));

        ChatMessageResult result = chatMessageService.sendMessage(1L, 10L, 7L,
                new ChatMessageCreateRequest("idem-set", MessageType.SETLOG_SHARE, null, null, 77L));

        assertThat(result.created()).isTrue();
        verify(setlogQueryService).requireShareableSetlog(77L, 7L);
        verify(chatRoomRepository).activateAndTouchLastMessageAt(1L);
    }

    @Test
    void reusingKeyWithDifferentMediaIdIsRejected() {
        ChatMessage original = mediaMsg(1L, 10L, MessageType.IMAGE, 501L, "idem-img");
        ChatMessageAttachment attachment = mock(ChatMessageAttachment.class);
        when(attachment.getMediaId()).thenReturn(501L);
        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "idem-img"))
                .thenReturn(Optional.of(original));
        when(attachmentRepository.findByMessageId(1L)).thenReturn(Optional.of(attachment));

        assertThatThrownBy(() -> chatMessageService.sendMessage(1L, 10L, 7L,
                new ChatMessageCreateRequest("idem-img", MessageType.IMAGE, null, 999L, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_DUPLICATE_MESSAGE);
    }

    @Test
    void reusingKeyWithDifferentSetlogIdIsRejected() {
        ChatMessage original = setlogShareMsg(1L, 10L, 77L, "idem-set");
        when(chatMessageRepository.findByRoomIdAndClientMessageId(1L, "idem-set"))
                .thenReturn(Optional.of(original));

        assertThatThrownBy(() -> chatMessageService.sendMessage(1L, 10L, 7L,
                new ChatMessageCreateRequest("idem-set", MessageType.SETLOG_SHARE, null, null, 88L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_DUPLICATE_MESSAGE);
    }

    // ---------- helpers ----------

    private static ChatMessageCreateRequest request(String clientMessageId, String body) {
        return new ChatMessageCreateRequest(clientMessageId, body);
    }

    private static ChatMessageResponse responseWithNickname(String nickname) {
        return new ChatMessageResponse(
                1L, 1L, "PET", 10L, nickname, "TEXT", "body", null, null, null, "idem", Instant.now());
    }

    private static MessageUpsert upsert(long id, boolean created) {
        MessageUpsert result = mock(MessageUpsert.class);
        lenient().when(result.getId()).thenReturn(id);
        lenient().when(result.getCreated()).thenReturn(created);
        return result;
    }

    private static ChatMessage textMsg(long id, Long senderPetId, String body, String clientMessageId) {
        return mockMsg(id, SenderType.PET, senderPetId, MessageType.TEXT, body, null, null, clientMessageId);
    }

    private static ChatMessage systemMsg(long id, String body) {
        return mockMsg(id, SenderType.SYSTEM, null, MessageType.SYSTEM, body, null, null, null);
    }

    private static ChatMessage cardMsg(long id, Long creatorPetId, Long meetingCardId, String clientMessageId) {
        return mockMsg(id, SenderType.PET, creatorPetId, MessageType.CARD, null, meetingCardId, null, clientMessageId);
    }

    private static ChatMessage mediaMsg(long id, Long senderPetId, MessageType type, Long mediaId, String clientMessageId) {
        return mockMsg(id, SenderType.PET, senderPetId, type, null, null, null, clientMessageId);
    }

    private static ChatMessage setlogShareMsg(long id, Long senderPetId, Long setlogId, String clientMessageId) {
        return mockMsg(id, SenderType.PET, senderPetId, MessageType.SETLOG_SHARE, null, null, setlogId, clientMessageId);
    }

    private static ChatMessage mockMsg(long id, SenderType senderType, Long senderPetId, MessageType type,
                                       String body, Long meetingCardId, Long sharedSetlogId, String clientMessageId) {
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
        lenient().when(msg.getSharedSetlogId()).thenReturn(sharedSetlogId);
        lenient().when(msg.getClientMessageId()).thenReturn(clientMessageId);
        return msg;
    }
}
