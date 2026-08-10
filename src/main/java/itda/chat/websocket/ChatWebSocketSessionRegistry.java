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

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> expiries = new ConcurrentHashMap<>();
    private final TaskScheduler scheduler;
    private final ThreadPoolTaskScheduler ownedScheduler;

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

    void bind(WebSocketSession session) {
        sessions.put(session.getId(), session);
    }

    /**
     * Schedules the close for this session's token expiry. A second CONNECT on one session
     * replaces the pending close instead of stacking another one.
     */
    void scheduleExpiry(String sessionId, Instant expiresAt) {
        if (sessionId == null || expiresAt == null) {
            return;
        }
        ScheduledFuture<?> previous = expiries.put(
                sessionId,
                scheduler.schedule(() -> closeExpired(sessionId), expiresAt)
        );
        cancel(previous);
    }

    /**
     * Drops a session and its pending close. Idempotent — DISCONNECT and the transport close
     * callback both land here, and a session may report DISCONNECT more than once.
     */
    void forget(String sessionId) {
        if (sessionId == null) {
            return;
        }
        cancel(expiries.remove(sessionId));
        sessions.remove(sessionId);
    }

    private void closeExpired(String sessionId) {
        expiries.remove(sessionId);
        WebSocketSession session = sessions.remove(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception exception) {
            log.warn("Expired WebSocket session close failed sessionId={} exceptionType={}",
                    sessionId, exception.getClass().getSimpleName());
        }
    }

    private void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    @PreDestroy
    void shutdown() {
        if (ownedScheduler != null) {
            ownedScheduler.shutdown();
        }
    }
}
