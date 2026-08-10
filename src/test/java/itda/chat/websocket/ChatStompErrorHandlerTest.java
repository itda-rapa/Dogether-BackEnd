package itda.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import tools.jackson.databind.ObjectMapper;

class ChatStompErrorHandlerTest {

    @Test
    void nonBusinessExceptionProducesInternalErrorStompFrame() {
        ChatStompErrorHandler handler = new ChatStompErrorHandler(new ObjectMapper());
        Message<byte[]> clientMessage = MessageBuilder.withPayload(new byte[0]).build();

        Message<byte[]> error = handler.handleClientMessageProcessingError(
                clientMessage,
                new IllegalStateException("unexpected inbound failure")
        );

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(error);
        String payload = new String(error.getPayload(), StandardCharsets.UTF_8);
        assertThat(accessor.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(payload).contains("\"code\":\"INTERNAL_ERROR\"");
        assertThat(payload).doesNotContain("\"code\":\"UNAUTHORIZED\"");
    }
}
