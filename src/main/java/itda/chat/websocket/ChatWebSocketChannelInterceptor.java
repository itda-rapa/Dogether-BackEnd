package itda.chat.websocket;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.security.service.AccessTokenSession;
import itda.common.security.service.TokenProvider;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import lombok.extern.slf4j.Slf4j;

/**
 * Authenticates CONNECT frames and applies the explicit WebSocket allowlist manually.
 *
 * <p>The original WebSocket contract describes Spring Security messaging authorization as a
 * preferred design, not a mandatory dependency. Keeping the allowlist here makes the order
 * explicit: session authentication and liveness are validated before command authorization.
 */
@Component
@ConditionalOnProperty(prefix = "app.websocket", name = "enabled", havingValue = "true")
@Slf4j
public class ChatWebSocketChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern DIRECT_SEND_DESTINATION = Pattern.compile(
            "^/app/chat/direct/rooms/(\\d+)/messages$"
    );

    private final TokenProvider tokenProvider;
    private final UserRepository userRepository;
    private final Clock clock;

    public ChatWebSocketChannelInterceptor(
            TokenProvider tokenProvider,
            UserRepository userRepository,
            Clock clock
    ) {
        this.tokenProvider = tokenProvider;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Autowired
    public ChatWebSocketChannelInterceptor(
            TokenProvider tokenProvider,
            UserRepository userRepository
    ) {
        this(tokenProvider, userRepository, Clock.systemUTC());
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                message,
                StompHeaderAccessor.class
        );
        if (accessor == null) {
            accessor = StompHeaderAccessor.wrap(message);
        }
        if (accessor.getCommand() == null) {
            return message;
        }

        return switch (accessor.getCommand()) {
            case CONNECT, STOMP -> authenticate(accessor, message);
            case SUBSCRIBE -> authorizeSubscription(accessor, message);
            case SEND -> authorizeSend(accessor, message);
            default -> message;
        };
    }

    private Message<?> authenticate(StompHeaderAccessor accessor, Message<?> message) {
        String authorization = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw unauthorized();
        }
        String rawToken = authorization.substring(BEARER_PREFIX.length()).trim();
        if (rawToken.isEmpty()) {
            throw unauthorized();
        }

        AccessTokenSession tokenSession = tokenProvider.parseAccessTokenSession(rawToken)
                .orElseThrow(this::unauthorized);
        User user = userRepository.findById(tokenSession.userId())
                .filter(User::isActive)
                .orElseThrow(this::unauthorized);

        WebSocketPrincipal principal = new WebSocketPrincipal(user.getId());
        accessor.setUser(new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        ));
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes == null) {
            throw unauthorized();
        }
        attributes.put(WebSocketSessionAttributeNames.USER_ID, tokenSession.userId());
        attributes.put(WebSocketSessionAttributeNames.EXPIRES_AT, tokenSession.expiresAt());
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
    }

    private Message<?> authorizeSubscription(StompHeaderAccessor accessor, Message<?> message) {
        validateSession(accessor);
        String destination = accessor.getDestination();
        if (!ChatWebSocketDestinations.USER_CHAT_MESSAGES.equals(destination)
                && !ChatWebSocketDestinations.USER_CHAT_ERRORS.equals(destination)) {
            log.warn("WebSocket authorization rejected sessionId={} code={}",
                    accessor.getSessionId(), ErrorCode.FORBIDDEN.name());
            throw forbidden();
        }
        return message;
    }

    private Message<?> authorizeSend(StompHeaderAccessor accessor, Message<?> message) {
        validateSession(accessor);
        String destination = accessor.getDestination();
        if (destination == null || !DIRECT_SEND_DESTINATION.matcher(destination).matches()) {
            log.warn("WebSocket authorization rejected sessionId={} code={}",
                    accessor.getSessionId(), ErrorCode.FORBIDDEN.name());
            throw forbidden();
        }
        return message;
    }

    private void validateSession(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        Object userIdValue = attributes == null
                ? null
                : attributes.get(WebSocketSessionAttributeNames.USER_ID);
        Object expiresAtValue = attributes == null
                ? null
                : attributes.get(WebSocketSessionAttributeNames.EXPIRES_AT);
        if (!(userIdValue instanceof Long userId)
                || !(expiresAtValue instanceof java.time.Instant expiresAt)
                || !expiresAt.isAfter(clock.instant())
                || accessor.getUser() == null
                || !userId.toString().equals(accessor.getUser().getName())) {
            log.warn("WebSocket session validation rejected sessionId={} code={}",
                    accessor.getSessionId(), ErrorCode.UNAUTHORIZED.name());
            throw unauthorized();
        }
        userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(this::unauthorized);
    }

    private BusinessException unauthorized() {
        return new BusinessException(ErrorCode.UNAUTHORIZED);
    }

    private BusinessException forbidden() {
        return new BusinessException(ErrorCode.FORBIDDEN);
    }
}
