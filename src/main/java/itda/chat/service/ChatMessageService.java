package itda.chat.service;

import itda.chat.domain.ChatMessage;
import itda.chat.domain.MessageType;
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
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatMessageService {

    private static final int MAX_BODY_LENGTH = 2000;
    private static final int MAX_CLIENT_MESSAGE_ID_LENGTH = 64;

    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomParticipantRepository participantRepository;
    private final GreetingRepository greetingRepository;

    /**
     * Send a user-authored message.
     *
     * <p>M1 allows exactly one user-authored message type, {@code TEXT} (최신 제품 정책 §4).
     * The room and the sending Pet are parameters rather than request fields: a controller takes
     * the room from the URL path and the Pet from the caller's authenticated Active Pet, so a
     * client can neither address another room nor impersonate another Pet.
     *
     * <p>{@code clientMessageId} is mandatory: it backs the {@code uk_chat_message_client}
     * unique constraint, so a retried send returns the original message instead of duplicating it.
     */
    @Transactional
    public ChatMessageResult sendText(long roomId, long senderPetId, ChatMessageCreateRequest request) {
        if (request.clientMessageId() == null || request.clientMessageId().isBlank()) {
            throw new BusinessException(ErrorCode.CHAT_CLIENT_MESSAGE_ID_REQUIRED);
        }
        Optional<Greeting> greetingToRespond =
                greetingRepository
                        .findFirstByRoomIdAndToPet_IdAndStatusOrderByIdAsc(
                                roomId,
                                senderPetId,
                                GreetingStatus.SENT
                        );
        if (greetingToRespond.isEmpty()
                && greetingRepository
                .existsByRoomIdAndFromPet_IdAndStatus(
                        roomId,
                        senderPetId,
                        GreetingStatus.SENT
                )) {
            throw new BusinessException(ErrorCode.GREETING_REPLY_REQUIRED);
        }

        ChatMessageResult result = insert(roomId, SenderType.PET, senderPetId,
                MessageType.TEXT, request.body(), null, request.clientMessageId());
        if (result.created()) {
            greetingToRespond.ifPresent(greeting ->
                    greeting.markResponded(Instant.now())
            );
        }
        return result;
    }

    /**
     * Stores the server-defined first Greeting text without applying the
     * "wait for reply" gate that immediately becomes active afterward.
     */
    @Transactional
    public ChatMessageResult sendGreetingText(
            long roomId,
            long senderPetId,
            ChatMessageCreateRequest request
    ) {
        return insert(
                roomId,
                SenderType.PET,
                senderPetId,
                MessageType.TEXT,
                request.body(),
                null,
                request.clientMessageId()
        );
    }

    /**
     * Announce a meeting card in its room. Server-side only — the meeting-card flow (M1-030)
     * calls this after persisting the card, so {@code meetingCardId} is always a card the server
     * just created.
     */
    @Transactional
    public ChatMessageResult postCard(long roomId, long creatorPetId, long meetingCardId, String clientMessageId) {
        return insert(roomId, SenderType.PET, creatorPetId,
                MessageType.CARD, null, meetingCardId, clientMessageId);
    }

    /**
     * Publish a system notice. Server-side only.
     *
     * <p>{@code clientMessageId} may be null: PostgreSQL treats NULLs as distinct within a UNIQUE
     * constraint, so repeated notices in one room are all stored. Pass a deterministic key when a
     * notice must be emitted at most once.
     */
    @Transactional
    public ChatMessageResult postSystem(long roomId, String body, String clientMessageId) {
        return insert(roomId, SenderType.SYSTEM, null,
                MessageType.SYSTEM, body, null, clientMessageId);
    }

    /**
     * Shared write path for every message type.
     *
     * <p>The {@code uk_chat_message_client} unique constraint is the concurrency gate, not an
     * application-level check: concurrent sends of the same key both reach the INSERT, exactly one
     * row survives, and the statement hands each caller that row's id plus whether it was the one
     * that wrote it.
     */
    private ChatMessageResult insert(long roomId, SenderType senderType, Long senderPetId,
                                     MessageType type, String body, Long meetingCardId, String clientMessageId) {
        requireValidPayload(type, body, clientMessageId);

        // Fast path: a message already stored under this key wins outright. It deliberately does
        // not advance last_message_at — a retry is not new room activity.
        if (clientMessageId != null) {
            var existing = chatMessageRepository.findByRoomIdAndClientMessageId(roomId, clientMessageId);
            if (existing.isPresent()) {
                ChatMessage found = existing.get();
                requireSamePayload(found, senderPetId, type, body, meetingCardId);
                return new ChatMessageResult(found, false);
            }
        }

        // Serialize message insertion with lifecycle cleanup. A cleanup transaction may hold
        // this room row while deleting it; waiting for the same FOR UPDATE lock makes the sender
        // observe a missing room after cleanup commits instead of reaching the chat_messages FK
        // and surfacing a raw DataIntegrityViolationException.
        chatRoomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        // Only members may speak in a room. This is a chat invariant rather than an access-control
        // detail, so it belongs here and not in a controller — and no DB constraint covers it:
        // ck_chat_message_sender only checks that a PET sender has an id at all.
        if (senderPetId != null
                && !participantRepository.existsByRoomIdAndPetIdAndLeftAtIsNull(roomId, senderPetId)) {
            throw new BusinessException(ErrorCode.CHAT_SENDER_NOT_PARTICIPANT);
        }

        MessageUpsert upsert = chatMessageRepository.insertMessageOnConflictWithReturning(
                roomId, senderType.name(), senderPetId, type.name(), body, meetingCardId, clientMessageId);

        ChatMessage message = chatMessageRepository.findById(upsert.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));

        // Losing the insert race returns the winner's row, which may not be ours.
        requireSamePayload(message, senderPetId, type, body, meetingCardId);

        boolean created = Boolean.TRUE.equals(upsert.getCreated());
        if (created) {
            // Only a genuine insert counts as activity. A concurrent retry reaches this point too —
            // it passed the fast-path lookup before the winner committed — and advancing the
            // timestamp for it would contradict the sequential retry above, which returns early
            // and touches nothing.
            chatRoomRepository.activateAndTouchLastMessageAt(roomId);
        }
        return new ChatMessageResult(message, created);
    }

    /**
     * Guards the payload here rather than leaning on {@code ck_chat_message_payload} alone.
     * Server-side callers ({@code postCard}, {@code postSystem}) never pass through bean
     * validation, and a constraint violation would surface as a raw persistence exception instead
     * of a {@code BusinessException}.
     */
    private void requireValidPayload(MessageType type, String body, String clientMessageId) {
        if (type == MessageType.CARD) {
            if (body != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        } else if (body == null || body.isBlank() || body.length() > MAX_BODY_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (clientMessageId != null && clientMessageId.length() > MAX_CLIENT_MESSAGE_ID_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    /**
     * An idempotency key identifies one specific message. Reusing it with different content is a
     * client bug rather than a retry, so reject it instead of silently returning something else.
     */
    private void requireSamePayload(ChatMessage stored, Long senderPetId, MessageType type,
                                    String body, Long meetingCardId) {
        if (stored.getType() != type
                || !Objects.equals(stored.getSenderPetId(), senderPetId)
                || !Objects.equals(stored.getBody(), body)
                || !Objects.equals(stored.getMeetingCardId(), meetingCardId)) {
            throw new BusinessException(ErrorCode.CHAT_DUPLICATE_MESSAGE);
        }
    }
}
