package itda.chat.websocket;

import itda.chat.dto.ChatMessageCreateRequest;
import itda.chat.service.ChatQueryService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import tools.jackson.databind.ObjectMapper;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.validation.annotation.Validated;

@Controller
@Validated
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "app.websocket", name = "enabled", havingValue = "true")
public class ChatWebSocketController {

    private final ChatQueryService chatQueryService;
    private final ObjectMapper objectMapper;

    @MessageMapping("/chat/direct/rooms/{roomId}/messages")
    @SendToUser(destinations = ChatWebSocketDestinations.CHAT_MESSAGES, broadcast = false)
    public ChatSendAck sendDirectMessage(
            @DestinationVariable long roomId,
            @Valid @Payload ChatMessageCreateRequest request,
            Principal principal
    ) {
        long userId = parseUserId(principal);
        ChatQueryService.SendMessageResult result = chatQueryService.sendMessage(
                userId,
                roomId,
                request
        );
        return new ChatSendAck(
                ChatWebSocketEventType.CHAT_SEND_ACK,
                roomId,
                result.data().messageId(),
                result.data().clientMessageId(),
                !result.created()
        );
    }

    @MessageExceptionHandler(BusinessException.class)
    @SendToUser(destinations = ChatWebSocketDestinations.CHAT_ERRORS, broadcast = false)
    public ChatWebSocketErrorPayload handleBusinessException(
            BusinessException exception,
            Message<?> message
    ) {
        ErrorCode code = exception.getErrorCode();
        return errorPayload(code, message, exception);
    }

    @MessageExceptionHandler(Exception.class)
    @SendToUser(destinations = ChatWebSocketDestinations.CHAT_ERRORS, broadcast = false)
    public ChatWebSocketErrorPayload handleUnexpectedException(
            Exception exception,
            Message<?> message
    ) {
        log.error("Unexpected WebSocket message error exceptionType={}",
                exception.getClass().getSimpleName());
        return errorPayload(ErrorCode.INTERNAL_ERROR, message, exception);
    }

    @MessageExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class
    })
    @SendToUser(destinations = ChatWebSocketDestinations.CHAT_ERRORS, broadcast = false)
    public ChatWebSocketErrorPayload handleValidationException(
            Exception exception,
            Message<?> message
    ) {
        return errorPayload(ErrorCode.VALIDATION_FAILED, message, exception);
    }

    private ChatWebSocketErrorPayload errorPayload(
            ErrorCode code,
            Message<?> message,
            Exception exception
    ) {
        ChatWebSocketRequestContext context = ChatWebSocketRequestContext.from(
                message,
                exception,
                objectMapper
        );
        return new ChatWebSocketErrorPayload(
                ChatWebSocketEventType.CHAT_ERROR,
                code.name(),
                code.getDescription(),
                context.roomId(),
                context.clientMessageId()
        );
    }

    private long parseUserId(Principal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        try {
            return Long.parseLong(principal.getName());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
