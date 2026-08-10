package itda.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.security.service.AccessTokenSession;
import itda.common.security.service.TokenProvider;
import itda.user.domain.Role;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

class ChatWebSocketChannelInterceptorTest {

    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    private TokenProvider tokenProvider;
    private UserRepository userRepository;
    private ChatWebSocketSessionRegistry sessionRegistry;
    private User user;
    private ChatWebSocketChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        tokenProvider = mock(TokenProvider.class);
        userRepository = mock(UserRepository.class);
        sessionRegistry = mock(ChatWebSocketSessionRegistry.class);
        user = mock(User.class);
        when(user.getId()).thenReturn(7L);
        when(user.getRole()).thenReturn(Role.USER);
        when(user.isActive()).thenReturn(true);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        interceptor = new ChatWebSocketChannelInterceptor(
                tokenProvider,
                userRepository,
                sessionRegistry,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void connectCreatesUserIdPrincipalAndStoresOnlySessionIdentity() {
        when(tokenProvider.parseAccessTokenSession("token"))
                .thenReturn(Optional.of(new AccessTokenSession(7L, NOW.plusSeconds(60))));

        Message<?> authenticated = interceptor.preSend(
                connectMessage("token"),
                mock(MessageChannel.class)
        );
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(authenticated);

        assertThat(accessor.getUser().getName()).isEqualTo("7");
        assertThat(accessor.getSessionAttributes())
                .containsOnlyKeys(
                        WebSocketSessionAttributeNames.USER_ID,
                        WebSocketSessionAttributeNames.EXPIRES_AT
                )
                .containsEntry(WebSocketSessionAttributeNames.USER_ID, 7L)
                .containsEntry(WebSocketSessionAttributeNames.EXPIRES_AT, NOW.plusSeconds(60));
    }

    @Test
    void stompHandshakeCreatesUserIdPrincipalAndStoresOnlySessionIdentity() {
        when(tokenProvider.parseAccessTokenSession("token"))
                .thenReturn(Optional.of(new AccessTokenSession(7L, NOW.plusSeconds(60))));

        Message<?> authenticated = interceptor.preSend(
                connectMessage(StompCommand.STOMP, "token"),
                mock(MessageChannel.class)
        );
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(authenticated);

        assertThat(accessor.getUser().getName()).isEqualTo("7");
        assertThat(accessor.getSessionAttributes())
                .containsOnlyKeys(
                        WebSocketSessionAttributeNames.USER_ID,
                        WebSocketSessionAttributeNames.EXPIRES_AT
                );
    }

    @Test
    void connectSchedulesTheSessionCloseAtTokenExpiry() {
        when(tokenProvider.parseAccessTokenSession("token"))
                .thenReturn(Optional.of(new AccessTokenSession(7L, NOW.plusSeconds(60))));

        interceptor.preSend(connectMessage("token"), mock(MessageChannel.class));

        verify(sessionRegistry).scheduleExpiry("session-1", NOW.plusSeconds(60));
    }

    @Test
    void rejectedConnectSchedulesNothing() {
        assertThatThrownBy(() -> interceptor.preSend(
                connectMessage(null),
                mock(MessageChannel.class)
        )).isInstanceOf(BusinessException.class);

        verify(sessionRegistry, org.mockito.Mockito.never())
                .scheduleExpiry(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void disconnectDropsThePendingClose() {
        StompHeaderAccessor disconnect = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        disconnect.setSessionId("session-1");

        interceptor.preSend(
                MessageBuilder.withPayload(new byte[0]).setHeaders(disconnect).build(),
                mock(MessageChannel.class)
        );

        verify(sessionRegistry).forget("session-1");
    }

    @Test
    void unsubscribeRequiresAnAuthenticatedSession() {
        Message<?> authenticated = authenticatedMessage();
        StompHeaderAccessor connected = StompHeaderAccessor.wrap(authenticated);
        StompHeaderAccessor unsubscribe = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
        unsubscribe.setSessionId("session-1");
        unsubscribe.setSessionAttributes(new HashMap<>(connected.getSessionAttributes()));
        unsubscribe.setUser(connected.getUser());

        assertThatCode(() -> interceptor.preSend(
                MessageBuilder.withPayload(new byte[0]).setHeaders(unsubscribe).build(),
                mock(MessageChannel.class)
        )).doesNotThrowAnyException();

        unsubscribe.setSessionAttributes(new HashMap<>());
        assertThatThrownBy(() -> interceptor.preSend(
                MessageBuilder.withPayload(new byte[0]).setHeaders(unsubscribe).build(),
                mock(MessageChannel.class)
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void heartbeatIsAllowed() {
        StompHeaderAccessor heartbeat = StompHeaderAccessor.createForHeartbeat();

        assertThatCode(() -> interceptor.preSend(
                MessageBuilder.withPayload(new byte[0]).setHeaders(heartbeat).build(),
                mock(MessageChannel.class)
        )).doesNotThrowAnyException();
    }

    @Test
    void unsupportedStompCommandsAreForbidden() {
        for (StompCommand command : List.of(
                StompCommand.ACK,
                StompCommand.NACK,
                StompCommand.BEGIN,
                StompCommand.COMMIT,
                StompCommand.ABORT
        )) {
            StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
            accessor.setSessionId("session-1");
            assertThatThrownBy(() -> interceptor.preSend(
                    MessageBuilder.withPayload(new byte[0]).setHeaders(accessor).build(),
                    mock(MessageChannel.class)
            )).isInstanceOfSatisfying(BusinessException.class, exception ->
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }
    }

    @Test
    void missingOrInvalidTokenIsUnauthorized() {
        assertThatThrownBy(() -> interceptor.preSend(
                connectMessage(null),
                mock(MessageChannel.class)
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void onlyAllowlistedSubscriptionsAreAccepted() {
        Message<?> authenticated = authenticatedMessage();
        StompHeaderAccessor connected = StompHeaderAccessor.wrap(authenticated);

        StompHeaderAccessor subscribe = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subscribe.setSessionId("session-1");
        subscribe.setSessionAttributes(new HashMap<>(connected.getSessionAttributes()));
        subscribe.setUser(connected.getUser());
        subscribe.setDestination(ChatWebSocketDestinations.USER_CHAT_MESSAGES);

        interceptor.preSend(
                MessageBuilder.withPayload(new byte[0]).setHeaders(subscribe).build(),
                mock(MessageChannel.class)
        );

        subscribe.setDestination("/topic/anything");
        assertThatThrownBy(() -> interceptor.preSend(
                MessageBuilder.withPayload(new byte[0]).setHeaders(subscribe).build(),
                mock(MessageChannel.class)
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void everySendRechecksExpiryAndActiveUser() {
        Message<?> authenticated = authenticatedMessage();
        StompHeaderAccessor connected = StompHeaderAccessor.wrap(authenticated);
        StompHeaderAccessor send = StompHeaderAccessor.create(StompCommand.SEND);
        send.setSessionId("session-1");
        send.setSessionAttributes(new HashMap<>(connected.getSessionAttributes()));
        send.setUser(connected.getUser());
        send.setDestination(ChatWebSocketDestinations.DIRECT_SEND_PREFIX + "123/messages");

        interceptor.preSend(
                MessageBuilder.withPayload(new byte[0]).setHeaders(send).build(),
                mock(MessageChannel.class)
        );

        verify(userRepository, org.mockito.Mockito.times(2)).findById(7L);
    }

    @Test
    void sendDestinationMustMatchExactNumericDirectPattern() {
        for (String destination : List.of(
                "/app/chat/direct/rooms/1/messages/extra",
                "/app/chat/direct/rooms/abc/messages",
                "/app/chat/group/rooms/1/messages",
                "/app/chat/direct/rooms/9223372036854775808/messages"
        )) {
            assertThatThrownBy(() -> interceptor.preSend(
                    authenticatedSend(destination),
                    mock(MessageChannel.class)
            )).isInstanceOfSatisfying(BusinessException.class, exception ->
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }
    }

    @Test
    void validDirectSendDestinationIsAccepted() {
        assertThatCode(() -> interceptor.preSend(
                authenticatedSend("/app/chat/direct/rooms/1/messages"),
                mock(MessageChannel.class)
        )).doesNotThrowAnyException();
    }

    private Message<?> authenticatedMessage() {
        when(tokenProvider.parseAccessTokenSession("token"))
                .thenReturn(Optional.of(new AccessTokenSession(7L, NOW.plusSeconds(60))));
        return interceptor.preSend(connectMessage("token"), mock(MessageChannel.class));
    }

    private Message<byte[]> authenticatedSend(String destination) {
        Message<?> authenticated = authenticatedMessage();
        StompHeaderAccessor connected = StompHeaderAccessor.wrap(authenticated);
        StompHeaderAccessor send = StompHeaderAccessor.create(StompCommand.SEND);
        send.setSessionId("session-1");
        send.setSessionAttributes(new HashMap<>(connected.getSessionAttributes()));
        send.setUser(connected.getUser());
        send.setDestination(destination);
        return MessageBuilder.withPayload(new byte[0]).setHeaders(send).build();
    }

    private Message<byte[]> connectMessage(String token) {
        return connectMessage(StompCommand.CONNECT, token);
    }

    private Message<byte[]> connectMessage(StompCommand command, String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("session-1");
        accessor.setSessionAttributes(new HashMap<>());
        if (token != null) {
            accessor.addNativeHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return MessageBuilder.withPayload(new byte[0]).setHeaders(accessor).build();
    }
}
