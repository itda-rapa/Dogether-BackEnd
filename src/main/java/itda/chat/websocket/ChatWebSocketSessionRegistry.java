package itda.chat.websocket;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Makes the access token TTL a hard upper bound on session lifetime.
 *
 * <p>Re-checking expiry on every SEND is not enough by itself: a session that subscribes and
 * never sends again is never re-examined, so an expired token would keep receiving broadcasts
 * for as long as the socket stays open. The close scheduled here is what actually enforces the
 * bound; the per-frame check in {@link ChatWebSocketChannelInterceptor} is the layer above it.
 *
 * <p>Closing a session needs a handle to it, which a {@code ScheduledFuture} does not provide —
 * hence the session map, populated by the transport decorator at connection time.
 */
@Component
@ConditionalOnProperty(prefix = "app.websocket", name = "enabled", havingValue = "true")
@Slf4j
public class ChatWebSocketSessionRegistry {

    private static final long CLOSE_RETRY_DELAY_MILLIS = 1_000L;

    private final Map<String, SessionRegistration> sessions = new ConcurrentHashMap<>();
    private final Map<String, ExpiryRegistration> expiries = new ConcurrentHashMap<>();
    private final TaskScheduler scheduler;
    private final ThreadPoolTaskScheduler ownedScheduler;
    private long nextGeneration;

    /**
     * Owns its scheduler rather than reusing the broker heartbeat one. Taking that bean would
     * put {@code ChatWebSocketConfig} → registry → scheduler → {@code ChatWebSocketConfig} in a
     * constructor cycle, and a blocked close must not stall heartbeats either.
     */
    public ChatWebSocketSessionRegistry() {
        ThreadPoolTaskScheduler created = new ThreadPoolTaskScheduler();
        created.setPoolSize(1);
        created.setThreadNamePrefix("dogether-ws-expiry-");
        created.initialize();
        this.ownedScheduler = created;
        this.scheduler = created;
    }

    ChatWebSocketSessionRegistry(TaskScheduler scheduler) {
        this.scheduler = scheduler;
        this.ownedScheduler = null;
    }

    synchronized void bind(WebSocketSession session) {
        String sessionId = session.getId();
        cancelExpiry(expiries.remove(sessionId));
        sessions.put(sessionId, new SessionRegistration(session, nextGeneration()));
    }

    /**
     * Schedules the close for this session's token expiry. A second CONNECT on one session
     * replaces the pending close instead of stacking another one.
     */
    synchronized void scheduleExpiry(String sessionId, Instant expiresAt) {
        if (sessionId == null || expiresAt == null) {
            return;
        }
        long generation = nextGeneration();
        ScheduledFuture<?> future = scheduler.schedule(
                () -> closeExpired(sessionId, generation), expiresAt);
        SessionRegistration session = sessions.get(sessionId);
        if (session != null) {
            sessions.put(sessionId, new SessionRegistration(session.session(), generation));
        }
        ExpiryRegistration previous = expiries.put(
                sessionId, new ExpiryRegistration(future, generation));
        cancelExpiry(previous);
    }

    /**
     * Drops a session and its pending close. Idempotent — DISCONNECT and the transport close
     * callback both land here, and a session may report DISCONNECT more than once.
     */
    synchronized void forget(String sessionId) {
        if (sessionId == null) {
            return;
        }
        cancelExpiry(expiries.remove(sessionId));
        sessions.remove(sessionId);
    }

    private synchronized void closeExpired(String sessionId, long generation) {
        SessionRegistration registration = sessions.get(sessionId);
        ExpiryRegistration expiry = expiries.get(sessionId);
        if (registration == null || expiry == null
                || registration.generation() != generation
                || expiry.generation() != generation) {
            return;
        }
        WebSocketSession session = registration.session();
        if (!session.isOpen()) {
            removeCurrent(sessionId, generation);
            return;
        }
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
            removeCurrent(sessionId, generation);
        } catch (Exception exception) {
            log.warn("Expired WebSocket session close failed sessionId={} exceptionType={}",
                    sessionId, exception.getClass().getSimpleName());
            ScheduledFuture<?> retry = scheduler.schedule(
                    () -> closeExpired(sessionId, generation),
                    Instant.now().plusMillis(CLOSE_RETRY_DELAY_MILLIS));
            ExpiryRegistration current = expiries.get(sessionId);
            if (current != null && current.generation() == generation) {
                expiries.put(sessionId, new ExpiryRegistration(retry, generation));
            } else {
                retry.cancel(false);
            }
        }
    }

    private void removeCurrent(String sessionId, long generation) {
        SessionRegistration currentSession = sessions.get(sessionId);
        ExpiryRegistration currentExpiry = expiries.get(sessionId);
        if (currentSession != null && currentSession.generation() == generation) {
            sessions.remove(sessionId);
        }
        if (currentExpiry != null && currentExpiry.generation() == generation) {
            expiries.remove(sessionId);
        }
    }

    private long nextGeneration() {
        return ++nextGeneration;
    }

    private void cancelExpiry(ExpiryRegistration expiry) {
        if (expiry != null) {
            cancel(expiry.future());
        }
    }

    private void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private record SessionRegistration(WebSocketSession session, long generation) {
    }

    private record ExpiryRegistration(ScheduledFuture<?> future, long generation) {
    }

    @PreDestroy
    void shutdown() {
        if (ownedScheduler != null) {
            ownedScheduler.shutdown();
        }
    }
}
