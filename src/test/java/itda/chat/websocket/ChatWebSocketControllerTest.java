package itda.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.chat.dto.ChatMessageCreateRequest;
import itda.chat.dto.response.ChatMessageResponse;
import itda.chat.service.ChatQueryService;
import java.security.Principal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChatWebSocketControllerTest {

    @Test
    void directSendDelegatesToChatQueryServiceAndReturnsNewAck() {
        ChatQueryService chatQueryService = mock(ChatQueryService.class);
        ChatWebSocketController controller = new ChatWebSocketController(chatQueryService, new ObjectMapper());
        ChatMessageCreateRequest request = new ChatMessageCreateRequest("client-1", "hello");
        ChatMessageResponse response = new ChatMessageResponse(
                456L, 123L, "PET", 11L, "Mong", "TEXT", "hello", null, "client-1",
                Instant.parse("2026-08-07T12:00:00Z")
        );
        when(chatQueryService.sendMessage(7L, 123L, request))
                .thenReturn(new ChatQueryService.SendMessageResult(response, true));
        Principal principal = () -> "7";

        ChatSendAck ack = controller.sendDirectMessage(123L, request, principal);

        verify(chatQueryService).sendMessage(7L, 123L, request);
        assertThat(ack.eventType()).isEqualTo("CHAT_SEND_ACK");
        assertThat(ack.messageId()).isEqualTo(456L);
        assertThat(ack.replayed()).isFalse();
    }

    @Test
    void idempotentResultReturnsReplayedAck() {
        ChatQueryService chatQueryService = mock(ChatQueryService.class);
        ChatWebSocketController controller = new ChatWebSocketController(chatQueryService, new ObjectMapper());
        ChatMessageCreateRequest request = new ChatMessageCreateRequest("client-1", "hello");
        ChatMessageResponse response = new ChatMessageResponse(
                456L, 123L, "PET", 11L, "Mong", "TEXT", "hello", null, "client-1", null
        );
        when(chatQueryService.sendMessage(7L, 123L, request))
                .thenReturn(new ChatQueryService.SendMessageResult(response, false));

        ChatSendAck ack = controller.sendDirectMessage(123L, request, () -> "7");

        assertThat(ack.replayed()).isTrue();
    }
}
