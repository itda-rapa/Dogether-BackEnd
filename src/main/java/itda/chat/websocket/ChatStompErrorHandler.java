package itda.chat.websocket;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompConversionException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;
import tools.jackson.databind.ObjectMapper;

public class ChatStompErrorHandler extends StompSubProtocolErrorHandler {

    private final ObjectMapper objectMapper;

    public ChatStompErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Message<byte[]> handleClientMessageProcessingError(
            Message<byte[]> clientMessage,
            Throwable exception
    ) {
        ErrorCode code = findErrorCode(exception);
        ChatWebSocketRequestContext context = ChatWebSocketRequestContext.from(
                clientMessage,
                exception,
                objectMapper
        );
        ChatWebSocketErrorPayload payload = new ChatWebSocketErrorPayload(
                ChatWebSocketEventType.CHAT_ERROR,
                code.name(),
                code.getDescription(),
                context.roomId(),
                context.clientMessageId()
        );
        try {
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
            accessor.setMessage(code.getDescription());
            accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
            accessor.setLeaveMutable(true);
            return MessageBuilder.createMessage(
                    objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8),
                    accessor.getMessageHeaders()
            );
        } catch (Exception serializationException) {
            return super.handleClientMessageProcessingError(clientMessage, serializationException);
        }
    }

    private ErrorCode findErrorCode(Throwable exception) {
        boolean malformedStompFrame = false;
        Throwable current = exception;
        while (current != null) {
            if (current instanceof BusinessException businessException) {
                return businessException.getErrorCode();
            }
            if (isMalformedStompFrame(current)) {
                malformedStompFrame = true;
            }
            current = current.getCause();
        }
        return malformedStompFrame ? ErrorCode.VALIDATION_FAILED : ErrorCode.INTERNAL_ERROR;
    }

    private boolean isMalformedStompFrame(Throwable exception) {
        if (exception instanceof StompConversionException) {
            return true;
        }
        if (!(exception instanceof IllegalArgumentException)) {
            return false;
        }
        // Spring 7.0.8 parses an unknown command through StompCommand.valueOf and exposes the
        // resulting IllegalArgumentException directly, rather than wrapping it in
        // StompConversionException. Keep the classification scoped to the decoder so ordinary
        // application IllegalArgumentExceptions still report INTERNAL_ERROR.
        for (StackTraceElement frame : exception.getStackTrace()) {
            if ("org.springframework.messaging.simp.stomp.StompDecoder".equals(frame.getClassName())) {
                return true;
            }
        }
        return false;
    }
}
