package itda.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.databind.ObjectMapper;

class ChatWebSocketRequestContextTest {

    @Test
    void rawJsonUsesParsedClientMessageIdInsteadOfMatchingTextInsideBody() {
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SEND);
        headers.setDestination("/app/chat/direct/rooms/17/messages");
        String json = "{\"clientMessageId\":\"actual-id\","
                + "\"body\":\"literal \\\"clientMessageId\\\":\\\"wrong-id\\\"\"}";
        Message<byte[]> message = MessageBuilder.withPayload(json.getBytes(StandardCharsets.UTF_8))
                .setHeaders(headers)
                .build();

        ChatWebSocketRequestContext context = ChatWebSocketRequestContext.from(
                message,
                null,
                new ObjectMapper()
        );

        assertThat(context.roomId()).isEqualTo(17L);
        assertThat(context.clientMessageId()).isEqualTo("actual-id");
    }
}
