package itda.chat.dto;

import itda.chat.domain.ChatMessage;

/**
 * A stored message together with whether this call is the one that created it.
 *
 * <p>{@code created} carries the distinction the send-message contract needs: a fresh message
 * answers {@code 201}, while returning an existing message for a repeated {@code clientMessageId}
 * answers {@code 200}. It also gates the room activity timestamp — only a genuine insert counts as
 * new activity.
 */
public record ChatMessageResult(ChatMessage message, boolean created) {}
