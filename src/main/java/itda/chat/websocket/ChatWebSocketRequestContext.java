package itda.chat.websocket;

import itda.chat.dto.ChatMessageCreateRequest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import tools.jackson.databind.ObjectMapper;

record ChatWebSocketRequestContext(Long roomId, String clientMessageId) {

    private static final Pattern DIRECT_ROOM_DESTINATION = Pattern.compile(
            "^/app/chat/direct/rooms/(\\d+)(?:/.*)?$"
    );

    static ChatWebSocketRequestContext from(
            Message<?> message,
            Throwable exception,
            ObjectMapper objectMapper
    ) {
        Long roomId = null;
        String clientMessageId = null;

        if (message != null) {
            StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
            roomId = roomIdFrom(accessor.getDestination());
            ChatMessageCreateRequest request = requestFromPayload(message.getPayload(), objectMapper);
            if (request != null) {
                clientMessageId = request.clientMessageId();
            }
        }

        if (clientMessageId == null && exception instanceof MethodArgumentNotValidException validationException) {
            Object target = validationException.getBindingResult().getTarget();
            if (target instanceof ChatMessageCreateRequest request) {
                clientMessageId = request.clientMessageId();
            }
        }

        return new ChatWebSocketRequestContext(roomId, clientMessageId);
    }

    private static Long roomIdFrom(String destination) {
        if (destination == null) {
            return null;
        }
        Matcher matcher = DIRECT_ROOM_DESTINATION.matcher(destination);
        return matcher.matches() ? Long.valueOf(matcher.group(1)) : null;
    }

    private static ChatMessageCreateRequest requestFromPayload(Object payload, ObjectMapper objectMapper) {
        if (payload instanceof ChatMessageCreateRequest request) {
            return request;
        }
        if (objectMapper != null && payload instanceof byte[] bytes) {
            try {
                return objectMapper.readValue(bytes, ChatMessageCreateRequest.class);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }
}
