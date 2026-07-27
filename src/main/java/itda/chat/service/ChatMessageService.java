package itda.chat.service;

import itda.chat.domain.ChatMessage;
import itda.chat.domain.MessageType;
import itda.chat.domain.SenderType;
import itda.chat.dto.SendTextRequest;
import itda.chat.repository.ChatMessageRepository;
import itda.chat.repository.ChatRoomRepository;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatMessageService {

    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomRepository chatRoomRepository;

    /**
     * Send a user-authored message.
     *
     * <p>M1 allows exactly one user-authored message type, {@code TEXT} (최신 제품 정책 §4).
     * This is the only entry point a controller may bind to a request body — neither the message
     * type nor the sender type is a parameter, so a client cannot forge a {@code CARD} or
     * {@code SYSTEM} message.
     *
     * <p>{@code clientMessageId} is mandatory: it backs the {@code uk_chat_message_client}
     * unique constraint, so a retried send returns the original message instead of duplicating it.
     */
    @Transactional
    public ChatMessage sendText(SendTextRequest request) {
        if (request.senderPetId() == null) {
            throw new BusinessException(ErrorCode.CHAT_SENDER_REQUIRED);
        }
        if (request.clientMessageId() == null || request.clientMessageId().isBlank()) {
            throw new BusinessException(ErrorCode.CHAT_CLIENT_MESSAGE_ID_REQUIRED);
        }
        if (request.body() == null || request.body().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return insert(request.roomId(), SenderType.PET, request.senderPetId(),
                MessageType.TEXT, request.body(), null, request.clientMessageId());
    }

    /**
     * Announce a meeting card in its room. Server-side only — the meeting-card flow (M1-030)
     * calls this after persisting the card, so {@code meetingCardId} is always a card the server
     * just created.
     */
    @Transactional
    public ChatMessage postCard(long roomId, long creatorPetId, long meetingCardId, String clientMessageId) {
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
    public ChatMessage postSystem(long roomId, String body, String clientMessageId) {
        return insert(roomId, SenderType.SYSTEM, null,
                MessageType.SYSTEM, body, null, clientMessageId);
    }

    /**
     * Shared write path for every message type.
     *
     * <p>The {@code uk_chat_message_client} unique constraint is the concurrency gate, not an
     * application-level check: concurrent sends of the same key both reach the INSERT, exactly one
     * row survives, and {@code RETURNING id} hands each caller the id of that surviving row.
     */
    private ChatMessage insert(long roomId, SenderType senderType, Long senderPetId,
                               MessageType type, String body, Long meetingCardId, String clientMessageId) {
        // Fast path: a message already stored under this key wins outright. It deliberately does
        // not advance last_message_at — a retry is not new room activity.
        if (clientMessageId != null) {
            var existing = chatMessageRepository.findByRoomIdAndClientMessageId(roomId, clientMessageId);
            if (existing.isPresent()) {
                ChatMessage found = existing.get();
                requireSamePayload(found, senderPetId, type, body, meetingCardId);
                return found;
            }
        }

        chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        Long messageId = chatMessageRepository.insertMessageOnConflictWithReturning(
                roomId, senderType.name(), senderPetId, type.name(), body, meetingCardId, clientMessageId);

        ChatMessage message = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));

        // Losing the insert race returns the winner's row, which may not be ours.
        requireSamePayload(message, senderPetId, type, body, meetingCardId);

        chatRoomRepository.activateAndTouchLastMessageAt(roomId);
        return message;
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
