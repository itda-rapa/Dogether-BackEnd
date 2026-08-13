package itda.chat.websocket;

import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import itda.chat.domain.RoomType;
import itda.chat.dto.response.ChatMessageResponse;
import itda.chat.event.ChatMessageCommittedEvent;
import itda.chat.service.ChatRealtimeRecipientQueryService;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatMessageRealtimeListenerTest {

    @Test
    void nonDirectRoomDoesNotPublish() {
        ChatRealtimeRecipientQueryService recipientQueryService = mock(ChatRealtimeRecipientQueryService.class);
        ChatRealtimePublisher publisher = mock(ChatRealtimePublisher.class);
        ChatMessageRealtimeListener listener = new ChatMessageRealtimeListener(
                recipientQueryService,
                publisher
        );
        ChatMessageResponse message = new ChatMessageResponse(
                456L, 123L, "PET", 11L, "TEXT", "hello", null, "client-1", null
        );

        listener.onMessageCommitted(new ChatMessageCommittedEvent(RoomType.GROUP, message));

        verifyNoInteractions(recipientQueryService, publisher);
    }

    @Test
    void oneRecipientFailureDoesNotStopOtherRecipients() {
        ChatRealtimeRecipientQueryService recipientQueryService = mock(ChatRealtimeRecipientQueryService.class);
        ChatRealtimePublisher publisher = mock(ChatRealtimePublisher.class);
        when(recipientQueryService.findActiveRecipientUserIds(123L, 11L))
                .thenReturn(List.of(1L, 2L, 3L));
        doThrow(new IllegalStateException("broker unavailable"))
                .when(publisher)
                .publishToUser(eq(2L), any(ChatMessageCreatedWsEvent.class));
        ChatMessageRealtimeListener listener = new ChatMessageRealtimeListener(
                recipientQueryService,
                publisher
        );
        ChatMessageResponse message = new ChatMessageResponse(
                456L, 123L, "PET", 11L, "TEXT", "hello", null, "client-1", null
        );

        listener.onMessageCommitted(new ChatMessageCommittedEvent(RoomType.DIRECT, message));

        verify(publisher).publishToUser(eq(1L), any(ChatMessageCreatedWsEvent.class));
        verify(publisher).publishToUser(eq(2L), any(ChatMessageCreatedWsEvent.class));
        verify(publisher).publishToUser(eq(3L), any(ChatMessageCreatedWsEvent.class));
    }
}
