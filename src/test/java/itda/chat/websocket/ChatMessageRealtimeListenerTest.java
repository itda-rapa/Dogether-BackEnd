package itda.chat.websocket;

import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import org.mockito.ArgumentCaptor;

import itda.chat.domain.RoomType;
import itda.chat.dto.response.ChatMessageResponse;
import itda.chat.dto.response.SetlogMediaResponse;
import itda.chat.dto.response.SharedSetlogResponse;
import itda.chat.event.ChatMessageCommittedEvent;
import itda.chat.service.ChatRealtimeRecipientQueryService;
import itda.chat.service.SharedSetlogResponseMapper;
import itda.setlog.dto.ShareableSetlogView;
import itda.setlog.service.SetlogQueryService;
import java.util.List;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ChatMessageRealtimeListenerTest {

    @Test
    void nonDirectRoomDoesNotPublish() {
        ChatRealtimeRecipientQueryService recipientQueryService = mock(ChatRealtimeRecipientQueryService.class);
        SetlogQueryService setlogQueryService = mock(SetlogQueryService.class);
        ChatRealtimePublisher publisher = mock(ChatRealtimePublisher.class);
        ChatMessageRealtimeListener listener = new ChatMessageRealtimeListener(
                recipientQueryService,
                setlogQueryService,
                new SharedSetlogResponseMapper(),
                publisher
        );
        ChatMessageResponse message = new ChatMessageResponse(
                456L, 123L, "PET", 11L, "Mong", "TEXT", "hello", null, null, null, "client-1", null
        );

        listener.onMessageCommitted(new ChatMessageCommittedEvent(RoomType.GROUP, message));

        verifyNoInteractions(recipientQueryService, setlogQueryService, publisher);
    }

    @Test
    void oneRecipientFailureDoesNotStopOtherRecipients() {
        ChatRealtimeRecipientQueryService recipientQueryService = mock(ChatRealtimeRecipientQueryService.class);
        SetlogQueryService setlogQueryService = mock(SetlogQueryService.class);
        ChatRealtimePublisher publisher = mock(ChatRealtimePublisher.class);
        when(recipientQueryService.findActiveRecipientUserIds(123L, 11L))
                .thenReturn(List.of(1L, 2L, 3L));
        doThrow(new IllegalStateException("broker unavailable"))
                .when(publisher)
                .publishToUser(eq(2L), any(ChatMessageCreatedWsEvent.class));
        ChatMessageRealtimeListener listener = new ChatMessageRealtimeListener(
                recipientQueryService,
                setlogQueryService,
                new SharedSetlogResponseMapper(),
                publisher
        );
        ChatMessageResponse message = new ChatMessageResponse(
                456L, 123L, "PET", 11L, "Mong", "TEXT", "hello", null, null, null, "client-1", null
        );

        listener.onMessageCommitted(new ChatMessageCommittedEvent(RoomType.DIRECT, message));

        verify(publisher).publishToUser(eq(1L), any(ChatMessageCreatedWsEvent.class));
        verify(publisher).publishToUser(eq(2L), any(ChatMessageCreatedWsEvent.class));
        verify(publisher).publishToUser(eq(3L), any(ChatMessageCreatedWsEvent.class));
    }

    @Test
    void setlogShareIsRedactedForBlockedRecipientBeforePublishing() {
        ChatRealtimeRecipientQueryService recipientQueryService = mock(ChatRealtimeRecipientQueryService.class);
        SetlogQueryService setlogQueryService = mock(SetlogQueryService.class);
        ChatRealtimePublisher publisher = mock(ChatRealtimePublisher.class);
        when(recipientQueryService.findActiveRecipientUserIds(123L, 11L)).thenReturn(List.of(1L, 2L));
        when(setlogQueryService.findShareableSetlogViews(List.of(77L), 1L)).thenReturn(java.util.Map.of(
                77L, new ShareableSetlogView(77L, true, 11L, "Mong", "산책", 501L, "IMAGE",
                        "https://storage.example/visible", Instant.parse("2026-08-26T04:00:00Z"), 3)));
        when(setlogQueryService.findShareableSetlogViews(List.of(77L), 2L)).thenReturn(java.util.Map.of(
                77L, ShareableSetlogView.unavailable(77L)));
        ChatMessageRealtimeListener listener = new ChatMessageRealtimeListener(
                recipientQueryService, setlogQueryService, new SharedSetlogResponseMapper(), publisher);
        ChatMessageResponse message = new ChatMessageResponse(
                456L, 123L, "PET", 11L, "Mong", "SETLOG_SHARE", null, null,
                new SharedSetlogResponse(77L, true, null, 11L, "Mong", "산책",
                        new SetlogMediaResponse(501L, "IMAGE", "https://storage.example/sender", Instant.now()),
                        3, "/setlogs/77"), null, "client-1", null);

        listener.onMessageCommitted(new ChatMessageCommittedEvent(RoomType.DIRECT, message));

        ArgumentCaptor<ChatMessageCreatedWsEvent> payloads = ArgumentCaptor.forClass(ChatMessageCreatedWsEvent.class);
        verify(publisher, org.mockito.Mockito.times(2)).publishToUser(any(Long.class), payloads.capture());
        ChatMessageCreatedWsEvent allowed = payloads.getAllValues().get(0);
        ChatMessageCreatedWsEvent blocked = payloads.getAllValues().get(1);
        assertThat(allowed.message().sharedSetlog().media().url()).isEqualTo("https://storage.example/visible");
        assertThat(blocked.message().sharedSetlog())
                .extracting("available", "caption", "media", "detailPath")
                .containsExactly(false, null, null, null);
    }

    @Test
    void setlogShareIsRedactedWhenViewerAwareLookupFailsBeforePublishing() {
        ChatRealtimeRecipientQueryService recipientQueryService = mock(ChatRealtimeRecipientQueryService.class);
        SetlogQueryService setlogQueryService = mock(SetlogQueryService.class);
        ChatRealtimePublisher publisher = mock(ChatRealtimePublisher.class);
        when(recipientQueryService.findActiveRecipientUserIds(123L, 11L)).thenReturn(List.of(1L));
        doThrow(new IllegalStateException("setlog query unavailable"))
                .when(setlogQueryService)
                .findShareableSetlogViews(List.of(77L), 1L);
        ChatMessageRealtimeListener listener = new ChatMessageRealtimeListener(
                recipientQueryService, setlogQueryService, new SharedSetlogResponseMapper(), publisher);
        ChatMessageResponse message = new ChatMessageResponse(
                456L, 123L, "PET", 11L, "Mong", "SETLOG_SHARE", null, null,
                new SharedSetlogResponse(77L, true, null, 11L, "Mong", "산책",
                        new SetlogMediaResponse(501L, "IMAGE", "https://storage.example/sender", Instant.now()),
                        3, "/setlogs/77"), null, "client-1", null);

        listener.onMessageCommitted(new ChatMessageCommittedEvent(RoomType.DIRECT, message));

        ArgumentCaptor<ChatMessageCreatedWsEvent> payload = ArgumentCaptor.forClass(ChatMessageCreatedWsEvent.class);
        verify(publisher).publishToUser(eq(1L), payload.capture());
        assertThat(payload.getValue().message().sharedSetlog())
                .extracting("available", "caption", "media", "detailPath")
                .containsExactly(false, null, null, null);
    }
}
