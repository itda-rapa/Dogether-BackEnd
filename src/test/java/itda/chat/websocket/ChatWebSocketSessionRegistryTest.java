package itda.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * The scheduler captures its task instead of running it, so expiry fires on demand — scheduling
 * against real wall-clock time would make these tests either slow or flaky.
 */
class ChatWebSocketSessionRegistryTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-08-10T12:00:00Z");

    private final List<Instant> scheduledAt = new ArrayList<>();
    private final List<Runnable> tasks = new ArrayList<>();
    private final List<boolean[]> cancelled = new ArrayList<>();

    private ChatWebSocketSessionRegistry registry;
    private WebSocketSession session;

    @BeforeEach
    void setUp() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            tasks.add(invocation.getArgument(0));
            scheduledAt.add(invocation.getArgument(1));
            boolean[] cancelledFlag = new boolean[]{false};
            cancelled.add(cancelledFlag);
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            when(future.cancel(anyBoolean())).thenAnswer(cancelInvocation -> {
                cancelledFlag[0] = true;
                return true;
            });
            return future;
        });

        registry = new ChatWebSocketSessionRegistry(scheduler);
        session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);
    }

    private void runPendingTasks() {
        for (int i = 0; i < tasks.size(); i++) {
            if (!cancelled.get(i)[0]) {
                tasks.get(i).run();
            }
        }
    }

    @Test
    void expiredSubscribeOnlySessionIsClosedWithoutAnyFurtherFrame() throws IOException {
        registry.bind(session);
        registry.scheduleExpiry("session-1", EXPIRES_AT);

        assertThat(scheduledAt).containsExactly(EXPIRES_AT);
        runPendingTasks();

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void forgetCancelsThePendingCloseSoALiveSessionIsNeverTouched() throws IOException {
        registry.bind(session);
        registry.scheduleExpiry("session-1", EXPIRES_AT);
        registry.forget("session-1");

        assertThat(cancelled.get(0)[0]).isTrue();
        runPendingTasks();

        verify(session, never()).close(any());
    }

    @Test
    void forgetIsIdempotentBecauseDisconnectCanBeObservedMoreThanOnce() {
        registry.bind(session);
        registry.scheduleExpiry("session-1", EXPIRES_AT);

        assertThatCode(() -> {
            registry.forget("session-1");
            registry.forget("session-1");
            registry.forget("unknown-session");
            registry.forget(null);
        }).doesNotThrowAnyException();
    }

    @Test
    void secondConnectOnOneSessionReplacesThePendingCloseInsteadOfStacking() throws IOException {
        registry.bind(session);
        registry.scheduleExpiry("session-1", EXPIRES_AT);
        registry.scheduleExpiry("session-1", EXPIRES_AT.plusSeconds(600));

        assertThat(cancelled.get(0)[0]).isTrue();
        assertThat(cancelled.get(1)[0]).isFalse();
        assertThat(scheduledAt).containsExactly(EXPIRES_AT, EXPIRES_AT.plusSeconds(600));

        runPendingTasks();

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void staleExpiryCannotCloseSessionAfterAReplacementGeneration() throws IOException {
        registry.bind(session);
        registry.scheduleExpiry("session-1", EXPIRES_AT);
        registry.scheduleExpiry("session-1", EXPIRES_AT.plusSeconds(600));

        // The old task races with cancel(false); generation checking must make it harmless.
        tasks.get(0).run();
        verify(session, never()).close(any());

        tasks.get(1).run();
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void staleExpiryCannotCloseAReconnectedSessionWithTheSameSessionId() throws IOException {
        WebSocketSession oldSession = session;
        WebSocketSession newSession = mock(WebSocketSession.class);
        when(newSession.getId()).thenReturn("session-1");
        when(newSession.isOpen()).thenReturn(true);

        registry.bind(oldSession);
        registry.scheduleExpiry("session-1", EXPIRES_AT);
        registry.bind(newSession);
        registry.scheduleExpiry("session-1", EXPIRES_AT.plusSeconds(600));

        // cancel(false) may race with execution; the old generation must be harmless.
        tasks.get(0).run();
        verify(oldSession, never()).close(any());
        verify(newSession, never()).close(any());

        tasks.get(1).run();
        verify(oldSession, never()).close(any());
        verify(newSession).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void alreadyClosedSessionIsNotClosedAgain() throws IOException {
        when(session.isOpen()).thenReturn(false);
        registry.bind(session);
        registry.scheduleExpiry("session-1", EXPIRES_AT);

        runPendingTasks();

        verify(session, never()).close(any());
    }

    @Test
    void closeFailureKeepsTheSessionAndSchedulesARetry() throws IOException {
        doThrow(new IOException("boom")).doNothing().when(session).close(any());
        registry.bind(session);
        registry.scheduleExpiry("session-1", EXPIRES_AT);

        assertThatCode(this::runPendingTasks).doesNotThrowAnyException();
        assertThat(scheduledAt).hasSize(2);
        verify(session, org.mockito.Mockito.times(2)).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void missingSessionIdOrExpirySchedulesNothing() {
        registry.scheduleExpiry(null, EXPIRES_AT);
        registry.scheduleExpiry("session-1", null);

        assertThat(scheduledAt).isEmpty();
    }
}
