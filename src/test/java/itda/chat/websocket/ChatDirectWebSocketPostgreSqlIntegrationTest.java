package itda.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.jayway.jsonpath.JsonPath;
import itda.chat.dto.ChatMessageCreateRequest;
import itda.chat.repository.ChatRealtimeRecipientRepository;
import itda.chat.service.ChatMessageService;
import itda.common.security.CurrentUser;
import itda.common.security.service.TokenProvider;
import itda.user.domain.Role;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("postgres")
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.websocket.enabled=true",
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
        }
)
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.test.context.failure.threshold=1")
class ChatDirectWebSocketPostgreSqlIntegrationTest {

    private final ArrayBlockingQueue<String> sessionErrors = new ArrayBlockingQueue<>(10);
    private final ArrayBlockingQueue<Map<String, Object>> protocolErrors = new ArrayBlockingQueue<>(10);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private ChatRealtimeRecipientRepository recipientRepository;

    @Autowired
    private SimpUserRegistry userRegistry;

    @Autowired
    private itda.common.properties.JwtProperties jwtProperties;

    /**
     * A spy, not a mock, so every other test in this class keeps the real service. Only the
     * internal-error test stubs it, and the bean override is reset between methods.
     */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private itda.chat.service.ChatQueryService chatQueryService;

    /** Used only to force a non-{@code BusinessException} failure inside the inbound channel. */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private ChatWebSocketSessionRegistry sessionRegistry;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("truncate refresh_tokens, chat_messages, chat_room_participants, chat_rooms, pets, users restart identity cascade");
        sessionErrors.clear();
        protocolErrors.clear();
    }

    @Test
    void directSendReturnsAckToSenderAndCreatedEventToAllEligibleSessions() throws Exception {
        long roomId = createFixture();
        assertThat(recipientRepository.findActiveRecipientUserIds(roomId, 11L))
                .containsExactlyInAnyOrder(1L, 2L);
        String tokenA = issueToken(1L);
        String tokenB = issueToken(2L);

        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());

        ArrayBlockingQueue<Map<String, Object>> framesA1 = new ArrayBlockingQueue<>(4);
        ArrayBlockingQueue<Map<String, Object>> framesA2 = new ArrayBlockingQueue<>(4);
        ArrayBlockingQueue<Map<String, Object>> framesB1 = new ArrayBlockingQueue<>(4);

        StompSession a1 = connect(client, tokenA);
        StompSession a2 = connect(client, tokenA);
        StompSession b1 = connect(client, tokenB);
        try {
            subscribeMessages(a1, framesA1, "1", 1);
            subscribeMessages(a2, framesA2, "1", 2);
            subscribeMessages(b1, framesB1, "2", 1);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(userRegistry.getUser("1")).isNotNull();
                assertThat(userRegistry.getUser("2")).isNotNull();
            });

            StompHeaders sendHeaders = new StompHeaders();
            sendHeaders.setDestination("/app/chat/direct/rooms/" + roomId + "/messages");
            sendHeaders.setContentType(MediaType.APPLICATION_JSON);
            String clientMessageId = "client-e2e-1";
            a1.send(sendHeaders, new ChatMessageCreateRequest(clientMessageId, "hello"));

            List<Map<String, Object>> receivedA1 = collectUntil(
                    framesA1, Set.of("CHAT_SEND_ACK", "CHAT_MESSAGE_CREATED"), 10);
            List<Map<String, Object>> receivedA2 = collectUntil(
                    framesA2, Set.of("CHAT_MESSAGE_CREATED"), 10);
            List<Map<String, Object>> receivedB1 = collectUntil(
                    framesB1, Set.of("CHAT_MESSAGE_CREATED"), 10);
            receivedA2.addAll(drainFrames(framesA2, 500));
            receivedB1.addAll(drainFrames(framesB1, 500));

            Map<String, Object> ack = findFrame(receivedA1, "CHAT_SEND_ACK");
            Map<String, Object> createdA1 = findFrame(receivedA1, "CHAT_MESSAGE_CREATED");
            Map<String, Object> createdA2 = findFrame(receivedA2, "CHAT_MESSAGE_CREATED");
            Map<String, Object> createdB1 = findFrame(receivedB1, "CHAT_MESSAGE_CREATED");

            long persistedMessageId = jdbcTemplate.queryForObject(
                    "select id from chat_messages where room_id = ? and client_message_id = ?",
                    Long.class,
                    roomId,
                    clientMessageId
            );
            assertAck(ack, roomId, persistedMessageId, clientMessageId, false);
            assertCreated(createdA1, roomId, persistedMessageId, 11L, clientMessageId,
                    "A1 frames=" + framesA1 + " errors=" + sessionErrors);
            assertCreated(createdA2, roomId, persistedMessageId, 11L, clientMessageId,
                    "A2 frames=" + framesA2 + " errors=" + sessionErrors);
            assertCreated(createdB1, roomId, persistedMessageId, 11L, clientMessageId,
                    "B1 frames=" + framesB1 + " errors=" + sessionErrors);
            assertNoEvent(receivedA2, "CHAT_SEND_ACK");
            assertNoEvent(receivedB1, "CHAT_SEND_ACK");
            assertThat(a1.isConnected()).isTrue();
            assertThat(a2.isConnected()).isTrue();
            assertThat(b1.isConnected()).isTrue();
        } finally {
            a1.disconnect();
            a2.disconnect();
            b1.disconnect();
            client.stop();
        }
    }

    /**
     * The session subscribes and then sends nothing, so the per-frame expiry check in the
     * interceptor can never run again. Only the close scheduled at CONNECT can end it — without
     * that, an expired token keeps receiving broadcasts for as long as the socket stays open.
     */
    @Test
    void subscribeOnlySessionIsClosedWhenItsAccessTokenExpires() throws Exception {
        createFixture();
        // Truncated to whole seconds because the JWT `exp` claim has one-second granularity —
        // an instant with sub-second precision would be floored on the way into the token, and
        // the close would legitimately fire before the untruncated value.
        java.time.Instant expiresAt = java.time.Instant.now()
                .plusSeconds(5)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String shortLivedToken = issueTokenExpiringAt(1L, expiresAt);

        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompSession session = connect(client, shortLivedToken);

        try {
            subscribeMessages(session, new ArrayBlockingQueue<>(4), "1", 1);
            assertThat(session.isConnected()).isTrue();

            await().atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> assertThat(session.isConnected()).isFalse());

            // Guards against the test passing for the wrong reason: a session dropped by
            // anything other than the scheduled close would end before its token expired.
            assertThat(java.time.Instant.now())
                    .as("session must survive until its token actually expires")
                    .isAfterOrEqualTo(expiresAt);
        } finally {
            client.stop();
        }
    }

    /**
     * The scheduled close is a transport-level close, not a protocol error: the frontend
     * distinguishes it by close code, so 1008 is part of the contract. It also must not be
     * preceded by a STOMP {@code ERROR} frame — that channel belongs to frame-level rejections.
     */
    @Test
    void scheduledExpiryClosesWithPolicyViolationAndSendsNoErrorFrame() throws Exception {
        createFixture();
        java.time.Instant expiresAt = java.time.Instant.now()
                .plusSeconds(5)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String shortLivedToken = issueTokenExpiringAt(1L, expiresAt);

        ArrayBlockingQueue<String> frames = new ArrayBlockingQueue<>(8);
        ArrayBlockingQueue<Integer> closeCodes = new ArrayBlockingQueue<>(2);
        WebSocketHandler handler = new WebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                session.sendMessage(new TextMessage(
                        "CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer "
                                + shortLivedToken + "\n\n\0"
                ));
            }

            @Override
            public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
                if (message instanceof TextMessage textMessage) {
                    frames.offer(textMessage.getPayload());
                }
            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) {
                sessionErrors.offer("transport=" + exception.getClass().getSimpleName());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                closeCodes.offer(status.getCode());
            }

            @Override
            public boolean supportsPartialMessages() {
                return false;
            }
        };

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(
                        handler,
                        new WebSocketHttpHeaders(),
                        new java.net.URI("ws://localhost:" + port + ChatWebSocketDestinations.ENDPOINT)
                )
                .get(15, TimeUnit.SECONDS);

        try {
            assertThat(frames.poll(10, TimeUnit.SECONDS)).contains("CONNECTED");
            session.sendMessage(new TextMessage(
                    "SUBSCRIBE\nid:sub-1\ndestination:"
                            + ChatWebSocketDestinations.USER_CHAT_MESSAGES + "\n\n\0"
            ));

            Integer closeCode = closeCodes.poll(30, TimeUnit.SECONDS);
            assertThat(closeCode)
                    .as("scheduled expiry must close with POLICY_VIOLATION")
                    .isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
            assertThat(java.time.Instant.now())
                    .as("session must survive until its token actually expires")
                    .isAfterOrEqualTo(expiresAt);
            assertThat(frames)
                    .as("expiry close is not announced as a STOMP ERROR frame")
                    .noneMatch(frame -> frame.startsWith("ERROR"));
        } finally {
            if (session.isOpen()) {
                session.close();
            }
        }
    }

    /**
     * Pins the close code of the interceptor rejection path against the scheduled-expiry close.
     * The frontend is told to key off whether a STOMP {@code ERROR} frame arrived first rather
     * than off the code, so this test exists to keep the two codes from silently converging and
     * making that advice look unnecessary.
     */
    @Test
    void unauthorizedDestinationClosesWithProtocolErrorNotPolicyViolation() throws Exception {
        createFixture();
        String token = issueToken(1L);
        ArrayBlockingQueue<String> frames = new ArrayBlockingQueue<>(8);
        ArrayBlockingQueue<Integer> closeCodes = new ArrayBlockingQueue<>(2);

        WebSocketHandler handler = new WebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                session.sendMessage(new TextMessage(
                        "CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer "
                                + token + "\n\n\0"
                ));
            }

            @Override
            public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
                if (message instanceof TextMessage textMessage) {
                    frames.offer(textMessage.getPayload());
                }
            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) {
                sessionErrors.offer("transport=" + exception.getClass().getSimpleName());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                closeCodes.offer(status.getCode());
            }

            @Override
            public boolean supportsPartialMessages() {
                return false;
            }
        };

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(
                        handler,
                        new WebSocketHttpHeaders(),
                        new java.net.URI("ws://localhost:" + port + ChatWebSocketDestinations.ENDPOINT)
                )
                .get(15, TimeUnit.SECONDS);

        try {
            assertThat(frames.poll(10, TimeUnit.SECONDS)).contains("CONNECTED");
            session.sendMessage(new TextMessage(
                    "SEND\ndestination:/app/not-allowlisted\ncontent-type:application/json\n\n"
                            + "{\"clientMessageId\":\"x\",\"body\":\"y\"}\0"
            ));

            String error = frames.poll(10, TimeUnit.SECONDS);
            assertThat(error).contains("ERROR").contains("FORBIDDEN");

            Integer closeCode = closeCodes.poll(10, TimeUnit.SECONDS);
            assertThat(closeCode)
                    .as("interceptor rejection close code")
                    .isEqualTo(CloseStatus.PROTOCOL_ERROR.getCode())
                    .isNotEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
        } finally {
            if (session.isOpen()) {
                session.close();
            }
        }
    }

    /**
     * The controller-internal branch of {@code INTERNAL_ERROR}: unlike the transport-layer branch
     * it must reach {@code /user/queue/errors} and leave the session open. Reading the annotations
     * does not establish that — whether {@code @MessageExceptionHandler(Exception.class)} plus
     * {@code @SendToUser} actually survive an unexpected service failure is a runtime question.
     */
    @Test
    void unexpectedServiceExceptionReturnsInternalErrorAndKeepsSessionOpen() throws Exception {
        long roomId = createFixture();
        WebSocketStompClient client = newClient();
        StompSession session = connect(client, issueToken(1L));
        ArrayBlockingQueue<ChatWebSocketErrorPayload> errors = new ArrayBlockingQueue<>(2);

        try {
            subscribeErrors(session, errors, "1", 1);

            org.mockito.Mockito.doThrow(new IllegalStateException("unexpected failure"))
                    .when(chatQueryService).sendMessage(
                            org.mockito.ArgumentMatchers.anyLong(),
                            org.mockito.ArgumentMatchers.anyLong(),
                            org.mockito.ArgumentMatchers.any()
                    );

            StompHeaders headers = new StompHeaders();
            headers.setDestination("/app/chat/direct/rooms/" + roomId + "/messages");
            headers.setContentType(MediaType.APPLICATION_JSON);
            session.send(headers, new ChatMessageCreateRequest("internal-error-1", "boom"));

            ChatWebSocketErrorPayload error = errors.poll(10, TimeUnit.SECONDS);
            assertThat(error)
                    .as("errors=%s protocolErrors=%s sessionErrors=%s", errors, protocolErrors, sessionErrors)
                    .isNotNull();
            assertThat(error.eventType()).isEqualTo(ChatWebSocketEventType.CHAT_ERROR);
            assertThat(error.code()).isEqualTo("INTERNAL_ERROR");
            assertThat(error.roomId()).isEqualTo(roomId);
            assertThat(error.clientMessageId()).isEqualTo("internal-error-1");

            assertThat(protocolErrors)
                    .as("controller-internal failures must not surface as a STOMP ERROR frame")
                    .isEmpty();
            assertThat(session.isConnected())
                    .as("session must stay open, unlike the transport-layer INTERNAL_ERROR")
                    .isTrue();
        } finally {
            disconnectIfConnected(session);
            client.stop();
        }
    }

    /**
     * The transport-layer branch of {@code INTERNAL_ERROR}, which ends the opposite way to the
     * controller-internal one: a STOMP {@code ERROR} frame and then a closed session. Both
     * branches carry the same code, so the contract can only separate them if this is pinned too.
     */
    @Test
    void unexpectedInterceptorExceptionSendsInternalErrorStompFrameAndCloses() throws Exception {
        createFixture();
        String token = issueToken(1L);

        org.mockito.Mockito.doThrow(new IllegalStateException("registry failure"))
                .when(sessionRegistry).scheduleExpiry(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any()
                );

        ArrayBlockingQueue<String> frames = new ArrayBlockingQueue<>(8);
        ArrayBlockingQueue<Integer> closeCodes = new ArrayBlockingQueue<>(2);
        WebSocketHandler handler = new WebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                session.sendMessage(new TextMessage(
                        "CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer "
                                + token + "\n\n\0"
                ));
            }

            @Override
            public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
                if (message instanceof TextMessage textMessage) {
                    frames.offer(textMessage.getPayload());
                }
            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) {
                sessionErrors.offer("transport=" + exception.getClass().getSimpleName());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                closeCodes.offer(status.getCode());
            }

            @Override
            public boolean supportsPartialMessages() {
                return false;
            }
        };

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(
                        handler,
                        new WebSocketHttpHeaders(),
                        new java.net.URI("ws://localhost:" + port + ChatWebSocketDestinations.ENDPOINT)
                )
                .get(15, TimeUnit.SECONDS);

        try {
            String frame = frames.poll(10, TimeUnit.SECONDS);
            assertThat(frame)
                    .as("frames=%s sessionErrors=%s", frames, sessionErrors)
                    .isNotNull()
                    .startsWith("ERROR")
                    .contains("INTERNAL_ERROR");

            assertThat(closeCodes.poll(10, TimeUnit.SECONDS))
                    .as("transport-layer INTERNAL_ERROR must end the session")
                    .isNotNull();
        } finally {
            if (session.isOpen()) {
                session.close();
            }
        }
    }

    @Test
    void invalidPayloadReturnsValidationErrorAndKeepsSessionOpen() throws Exception {
        long roomId = createFixture();
        String tokenA = issueToken(1L);
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompSession session = connect(client, tokenA);
        ArrayBlockingQueue<ChatWebSocketErrorPayload> errors = new ArrayBlockingQueue<>(2);

        try {
            subscribeErrors(session, errors, "1", 1);

            StompHeaders sendHeaders = new StompHeaders();
            sendHeaders.setDestination("/app/chat/direct/rooms/" + roomId + "/messages");
            sendHeaders.setContentType(MediaType.APPLICATION_JSON);
            session.send(sendHeaders, new ChatMessageCreateRequest("", ""));

            ChatWebSocketErrorPayload error = errors.poll(10, TimeUnit.SECONDS);
            assertThat(error).isNotNull();
            assertThat(error.eventType()).isEqualTo(ChatWebSocketEventType.CHAT_ERROR);
            assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
            assertThat(error.roomId()).isEqualTo(roomId);
            assertThat(session.isConnected()).isTrue();
        } finally {
            session.disconnect();
            client.stop();
        }
    }

    @Test
    void blockedRecipientIsExcludedInEitherBlockDirection() {
        long roomId = createFixture();

        insertBlock(2L, 1L, 22L, 11L);
        assertThat(recipientRepository.findActiveRecipientUserIds(roomId, 11L))
                .containsExactly(1L);

        jdbcTemplate.update("delete from user_blocks");
        insertBlock(1L, 2L, 11L, 22L);
        assertThat(recipientRepository.findActiveRecipientUserIds(roomId, 11L))
                .containsExactly(1L);
    }

    @Test
    void senderRemainsIncludedWhenSenderHasBlockedOtherParticipant() {
        long roomId = createFixture();
        insertBlock(1L, 2L, 11L, 22L);

        assertThat(recipientRepository.findActiveRecipientUserIds(roomId, 11L))
                .contains(1L)
                .doesNotContain(2L);
    }

    @Test
    void inactiveRecipientIsExcluded() {
        long roomId = createFixture();
        jdbcTemplate.update("update users set account_status = 'SUSPENDED' where id = 2");

        assertThat(recipientRepository.findActiveRecipientUserIds(roomId, 11L))
                .containsExactly(1L);
    }

    @Test
    void recipientWithDifferentActivePetIsExcluded() {
        long roomId = createFixture();
        jdbcTemplate.update("update users set active_pet_id = null where id = 2");

        assertThat(recipientRepository.findActiveRecipientUserIds(roomId, 11L))
                .containsExactly(1L);
    }

    @Test
    void recipientWithSuspendedPetIsExcluded() {
        long roomId = createFixture();
        jdbcTemplate.update("update pets set status = 'SUSPENDED' where id = 22");

        assertThat(recipientRepository.findActiveRecipientUserIds(roomId, 11L))
                .containsExactly(1L);
    }

    @Test
    void recipientWithDeletedPetIsExcluded() {
        long roomId = createFixture();
        jdbcTemplate.update(
                "update pets set status = 'DELETED', deleted_at = now() where id = 22"
        );

        assertThat(recipientRepository.findActiveRecipientUserIds(roomId, 11L))
                .containsExactly(1L);
    }

    @Test
    void leftParticipantIsExcluded() {
        long roomId = createFixture();
        jdbcTemplate.update("update chat_room_participants set left_at = now() where room_id = ? and pet_id = 22",
                roomId);

        assertThat(recipientRepository.findActiveRecipientUserIds(roomId, 11L))
                .containsExactly(1L);
    }

    @Test
    void nullSenderPetIdExecutesSystemMessageRecipientBranch() {
        long roomId = createFixture();

        assertThat(recipientRepository.findActiveRecipientUserIds(roomId, null))
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void duplicateDirectSendReturnsReplayedAckAndNoSecondCreatedEvent() throws Exception {
        long roomId = createFixture();
        WebSocketStompClient client = newClient();
        StompSession a1 = connect(client, issueToken(1L));
        StompSession b1 = connect(client, issueToken(2L));
        ArrayBlockingQueue<Map<String, Object>> framesA = new ArrayBlockingQueue<>(6);
        ArrayBlockingQueue<Map<String, Object>> framesB = new ArrayBlockingQueue<>(6);
        String clientMessageId = "client-replay-1";

        try {
            subscribeMessages(a1, framesA, "1", 1);
            subscribeMessages(b1, framesB, "2", 1);
            sendText(a1, roomId, clientMessageId, "first");

            List<Map<String, Object>> firstA = collectUntil(
                    framesA, Set.of("CHAT_SEND_ACK", "CHAT_MESSAGE_CREATED"), 10);
            List<Map<String, Object>> firstB = collectUntil(
                    framesB, Set.of("CHAT_MESSAGE_CREATED"), 10);
            long persistedMessageId = jdbcTemplate.queryForObject(
                    "select id from chat_messages where room_id = ? and client_message_id = ?",
                    Long.class, roomId, clientMessageId);
            assertAck(findFrame(firstA, "CHAT_SEND_ACK"), roomId, persistedMessageId,
                    clientMessageId, false);
            assertCreated(findFrame(firstA, "CHAT_MESSAGE_CREATED"), roomId, persistedMessageId,
                    11L, clientMessageId, "first A frames=" + firstA);
            assertCreated(findFrame(firstB, "CHAT_MESSAGE_CREATED"), roomId, persistedMessageId,
                    11L, clientMessageId, "first B frames=" + firstB);

            sendText(a1, roomId, clientMessageId, "first");
            List<Map<String, Object>> retryA = collectUntil(
                    framesA, Set.of("CHAT_SEND_ACK"), 10);
            retryA.addAll(drainFrames(framesA, 500));
            List<Map<String, Object>> retryB = drainFrames(framesB, 500);
            assertAck(findFrame(retryA, "CHAT_SEND_ACK"), roomId, persistedMessageId,
                    clientMessageId, true);
            assertNoEvent(retryA, "CHAT_MESSAGE_CREATED");
            assertNoEvent(retryB, "CHAT_MESSAGE_CREATED");
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from chat_messages where room_id = ? and client_message_id = ?",
                    Integer.class, roomId, clientMessageId)).isEqualTo(1);
        } finally {
            disconnectIfConnected(a1);
            disconnectIfConnected(b1);
            client.stop();
        }
    }

    @Test
    void sendWithoutActivePetReturnsDomainErrorAndKeepsSessionOpen() throws Exception {
        long roomId = createFixture();
        jdbcTemplate.update("update users set active_pet_id = null where id = 1");
        WebSocketStompClient client = newClient();
        StompSession session = connect(client, issueToken(1L));
        ArrayBlockingQueue<ChatWebSocketErrorPayload> errors = new ArrayBlockingQueue<>(2);

        try {
            subscribeErrors(session, errors, "1", 1);
            sendText(session, roomId, "no-active-pet", "blocked");
            ChatWebSocketErrorPayload error = pollError(errors, "ACTIVE_PET_REQUIRED");
            assertThat(error.roomId()).isEqualTo(roomId);
            assertThat(error.clientMessageId()).isEqualTo("no-active-pet");
            assertThat(session.isConnected()).isTrue();
        } finally {
            disconnectIfConnected(session);
            client.stop();
        }
    }

    @Test
    void twoDomainFailuresKeepTheirOwnClientMessageIds() throws Exception {
        long roomId = createFixture();
        jdbcTemplate.update("update users set active_pet_id = null where id = 1");
        WebSocketStompClient client = newClient();
        StompSession session = connect(client, issueToken(1L));
        ArrayBlockingQueue<ChatWebSocketErrorPayload> errors = new ArrayBlockingQueue<>(4);

        try {
            subscribeErrors(session, errors, "1", 1);
            sendText(session, roomId, "p3-failure-1", "first failure");
            sendText(session, roomId, "p3-failure-2", "second failure");

            ChatWebSocketErrorPayload first = pollError(errors, "ACTIVE_PET_REQUIRED");
            ChatWebSocketErrorPayload second = pollError(errors, "ACTIVE_PET_REQUIRED");
            assertThat(first.roomId()).isEqualTo(roomId);
            assertThat(second.roomId()).isEqualTo(roomId);
            assertThat(java.util.List.of(first.clientMessageId(), second.clientMessageId()))
                    .containsExactlyInAnyOrder("p3-failure-1", "p3-failure-2");
            assertThat(session.isConnected()).isTrue();
        } finally {
            disconnectIfConnected(session);
            client.stop();
        }
    }

    @Test
    void nonParticipantAndBlockedUserReceiveDomainErrorsWithoutDisconnect() throws Exception {
        long roomId = createFixture();
        insertUser(3L, "c@example.com", "c#C001");
        insertPet(33L, 3L, "dog-c#C001");
        jdbcTemplate.update("update users set active_pet_id = 33 where id = 3");
        WebSocketStompClient client = newClient();
        StompSession nonParticipant = connect(client, issueToken(3L));
        ArrayBlockingQueue<ChatWebSocketErrorPayload> nonParticipantErrors = new ArrayBlockingQueue<>(2);
        StompSession blockedUser = null;
        ArrayBlockingQueue<ChatWebSocketErrorPayload> blockedErrors = new ArrayBlockingQueue<>(2);

        try {
            subscribeErrors(nonParticipant, nonParticipantErrors, "3", 1);
            sendText(nonParticipant, roomId, "non-participant", "blocked");
            assertError(nonParticipantErrors, "CHAT_ROOM_NOT_FOUND");
            assertThat(nonParticipant.isConnected()).isTrue();
            disconnectIfConnected(nonParticipant);

            insertBlock(1L, 2L, 11L, 22L);
            blockedUser = connect(client, issueToken(2L));
            subscribeErrors(blockedUser, blockedErrors, "2", 1);
            sendText(blockedUser, roomId, "blocked-user", "blocked");
            assertError(blockedErrors, "CHAT_ROOM_NOT_FOUND");
            assertThat(blockedUser.isConnected()).isTrue();
        } finally {
            disconnectIfConnected(nonParticipant);
            if (blockedUser != null) {
                disconnectIfConnected(blockedUser);
            }
            client.stop();
        }
    }

    @Test
    void greetingGateBlocksBeforeReplyAndAllowsBothSidesAfterReply() throws Exception {
        long roomId = createFixture();
        insertGreeting(roomId);
        WebSocketStompClient client = newClient();
        StompSession a1 = connect(client, issueToken(1L));
        StompSession b1 = connect(client, issueToken(2L));
        ArrayBlockingQueue<ChatWebSocketErrorPayload> errorsA = new ArrayBlockingQueue<>(2);
        ArrayBlockingQueue<Map<String, Object>> framesA = new ArrayBlockingQueue<>(6);
        ArrayBlockingQueue<Map<String, Object>> framesB = new ArrayBlockingQueue<>(6);

        try {
            subscribeErrors(a1, errorsA, "1", 1);
            subscribeMessages(a1, framesA, "1", 1);
            subscribeMessages(b1, framesB, "2", 1);
            sendText(a1, roomId, "before-reply", "blocked");
            assertError(errorsA, "GREETING_REPLY_REQUIRED");
            assertThat(a1.isConnected()).isTrue();

            sendText(b1, roomId, "greeting-reply", "reply");
            List<Map<String, Object>> replyFrames = collectUntil(
                    framesB, Set.of("CHAT_SEND_ACK"), 10);
            assertThat(findFrame(replyFrames, "CHAT_SEND_ACK")).isNotNull();

            sendText(a1, roomId, "after-reply", "allowed");
            List<Map<String, Object>> allowedFrames = collectUntil(
                    framesA, Set.of("CHAT_SEND_ACK"), 10);
            assertThat(findFrame(allowedFrames, "CHAT_SEND_ACK")).isNotNull();
            assertThat(jdbcTemplate.queryForObject(
                    "select status from greetings where room_id = ?", String.class, roomId))
                    .isEqualTo("RESPONDED");
        } finally {
            disconnectIfConnected(a1);
            disconnectIfConnected(b1);
            client.stop();
        }
    }

    @Test
    void archivedRoomIsRestoredToActiveByDirectSend() throws Exception {
        long roomId = createFixture();
        jdbcTemplate.update("update chat_rooms set status = 'ARCHIVED', archived_at = now() where id = ?", roomId);
        WebSocketStompClient client = newClient();
        StompSession session = connect(client, issueToken(1L));
        ArrayBlockingQueue<Map<String, Object>> frames = new ArrayBlockingQueue<>(4);

        try {
            subscribeMessages(session, frames, "1", 1);
            sendText(session, roomId, "archived-restore", "restore");
            List<Map<String, Object>> received = collectUntil(frames, Set.of("CHAT_SEND_ACK"), 10);
            assertThat(findFrame(received, "CHAT_SEND_ACK")).isNotNull();
            assertThat(jdbcTemplate.queryForObject(
                    "select status from chat_rooms where id = ?", String.class, roomId))
                    .isEqualTo("ACTIVE");
        } finally {
            disconnectIfConnected(session);
            client.stop();
        }
    }

    @Test
    void suspendedUserBeforeSendGetsStompErrorAndConnectionCloses() throws Exception {
        long roomId = createFixture();
        WebSocketStompClient client = newClient();
        StompSession session = connect(client, issueToken(1L));

        try {
            jdbcTemplate.update("update users set account_status = 'SUSPENDED' where id = 1");
            sendText(session, roomId, "suspended-send", "rejected");
            Map<String, Object> error = protocolErrors.poll(10, TimeUnit.SECONDS);
            assertThat(error)
                    .as("protocolErrors=%s sessionErrors=%s connected=%s", protocolErrors, sessionErrors, session.isConnected())
                    .isNotNull();
            assertThat(error.get("code")).isEqualTo("UNAUTHORIZED");
            await().atMost(Duration.ofSeconds(5)).until(() -> !session.isConnected());
        } finally {
            disconnectIfConnected(session);
            client.stop();
        }
    }

    @Test
    void unauthorizedDestinationGetsForbiddenStompErrorAndConnectionCloses() throws Exception {
        createFixture();
        WebSocketStompClient client = newClient();
        StompSession session = connect(client, issueToken(1L));

        try {
            StompHeaders headers = new StompHeaders();
            headers.setDestination("/app/not-allowlisted");
            headers.setContentType(MediaType.APPLICATION_JSON);
            session.send(headers, new ChatMessageCreateRequest("bad-destination", "rejected"));
            Map<String, Object> error = protocolErrors.poll(10, TimeUnit.SECONDS);
            assertThat(error).isNotNull();
            assertThat(error.get("code")).isEqualTo("FORBIDDEN");
            await().atMost(Duration.ofSeconds(5)).until(() -> !session.isConnected());
        } finally {
            disconnectIfConnected(session);
            client.stop();
        }
    }

    @Test
    void malformedStompFrameProducesValidationErrorAndCloses() throws Exception {
        createFixture();
        ArrayBlockingQueue<String> frames = new ArrayBlockingQueue<>(4);
        String token = issueToken(1L);
        WebSocketHandler handler = new WebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                session.sendMessage(new TextMessage(
                        "CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer "
                                + token + "\n\n\0"
                ));
            }

            @Override
            public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
                if (message instanceof TextMessage textMessage) {
                    frames.offer(textMessage.getPayload());
                }
            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) {
                sessionErrors.offer("transport=" + exception.getClass().getSimpleName());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                sessionErrors.offer("closed=" + status.getCode());
            }

            @Override
            public boolean supportsPartialMessages() {
                return false;
            }
        };
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(
                        handler,
                        new WebSocketHttpHeaders(),
                        new java.net.URI("ws://localhost:" + port + ChatWebSocketDestinations.ENDPOINT)
                )
                .get(15, TimeUnit.SECONDS);

        try {
            assertThat(frames.poll(10, TimeUnit.SECONDS)).contains("CONNECTED");
            session.sendMessage(new TextMessage("NOT_A_STOMP_COMMAND\n\n\0"));
            String error = frames.poll(10, TimeUnit.SECONDS);
            assertThat(error).contains("ERROR").contains("VALIDATION_FAILED").doesNotContain("UNAUTHORIZED");
            await().atMost(Duration.ofSeconds(5)).until(() -> !session.isOpen());
            // Distinct from the scheduled-expiry close (1008), so the two are separable by code
            // as well as by whether an ERROR frame preceded them.
            assertThat(sessionErrors).contains("closed=" + CloseStatus.PROTOCOL_ERROR.getCode());
        } finally {
            if (session.isOpen()) {
                session.close();
            }
        }
    }

    @Test
    void unsupportedStompCommandProducesForbiddenErrorAndCloses() throws Exception {
        createFixture();
        ArrayBlockingQueue<String> frames = new ArrayBlockingQueue<>(4);
        String token = issueToken(1L);
        WebSocketHandler handler = new WebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                session.sendMessage(new TextMessage(
                        "CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer "
                                + token + "\n\n\0"
                ));
            }

            @Override
            public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
                if (message instanceof TextMessage textMessage) {
                    frames.offer(textMessage.getPayload());
                }
            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) {
                sessionErrors.offer("transport=" + exception.getClass().getSimpleName());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                sessionErrors.offer("closed=" + status.getCode());
            }

            @Override
            public boolean supportsPartialMessages() {
                return false;
            }
        };
        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(
                        handler,
                        new WebSocketHttpHeaders(),
                        new java.net.URI("ws://localhost:" + port + ChatWebSocketDestinations.ENDPOINT)
                )
                .get(15, TimeUnit.SECONDS);

        try {
            assertThat(frames.poll(10, TimeUnit.SECONDS)).contains("CONNECTED");
            session.sendMessage(new TextMessage("ACK\nid:ack-1\n\n\0"));
            String error = frames.poll(10, TimeUnit.SECONDS);
            assertThat(error).contains("ERROR").contains("FORBIDDEN").doesNotContain("UNAUTHORIZED");
            await().atMost(Duration.ofSeconds(5)).until(() -> !session.isOpen());
        } finally {
            if (session.isOpen()) {
                session.close();
            }
        }
    }

    @Test
    void restCreatedTextReachesExistingWebSocketSubscriber() throws Exception {
        long roomId = createFixture();
        WebSocketStompClient client = newClient();
        StompSession b1 = connect(client, issueToken(2L));
        ArrayBlockingQueue<Map<String, Object>> framesB = new ArrayBlockingQueue<>(4);

        try {
            subscribeMessages(b1, framesB, "2", 1);
            String response = mockMvc.perform(MockMvcRequestBuilders.post(
                            "/chat/rooms/{roomId}/messages", roomId)
                    .with(user(new CurrentUser(1L, "a@example.com", Role.USER)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"clientMessageId\":\"rest-ws-1\",\"body\":\"rest\"}"))
                    .andExpect(MockMvcResultMatchers.status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            long messageId = ((Number) JsonPath.read(response, "$.data.messageId")).longValue();
            List<Map<String, Object>> received = collectUntil(
                    framesB, Set.of("CHAT_MESSAGE_CREATED"), 10);
            assertCreated(findFrame(received, "CHAT_MESSAGE_CREATED"), roomId, messageId,
                    11L, "rest-ws-1", "REST subscriber frames=" + received);
        } finally {
            disconnectIfConnected(b1);
            client.stop();
        }
    }

    @Test
    void afterMessageIdRecoversMessageCreatedWhileDisconnected() throws Exception {
        long roomId = createFixture();
        long firstId = chatMessageService.sendText(roomId, 11L,
                new ChatMessageCreateRequest("poll-first", "first")).message().getId();
        long missedId = chatMessageService.sendText(roomId, 22L,
                new ChatMessageCreateRequest("poll-missed", "missed")).message().getId();

        mockMvc.perform(MockMvcRequestBuilders.get("/chat/rooms/{roomId}/messages", roomId)
                        .param("afterMessageId", String.valueOf(firstId))
                        .with(user(new CurrentUser(2L, "b@example.com", Role.USER))))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.items").isArray())
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.items.length()").value(1))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.items[0].messageId")
                        .value((int) missedId));
    }

    @Test
    void afterMessageIdAtLatestReturnsNoAlreadySeenMessage() throws Exception {
        long roomId = createFixture();
        chatMessageService.sendText(roomId, 11L,
                new ChatMessageCreateRequest("poll-latest", "latest"));
        long latestId = jdbcTemplate.queryForObject(
                "select max(id) from chat_messages where room_id = ?", Long.class, roomId);

        mockMvc.perform(MockMvcRequestBuilders.get("/chat/rooms/{roomId}/messages", roomId)
                        .param("afterMessageId", String.valueOf(latestId))
                        .with(user(new CurrentUser(1L, "a@example.com", Role.USER))))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.items").isEmpty())
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.nextAfterMessageId")
                        .value((int) latestId));
    }

    @Test
    void rolledBackMessageDoesNotReachWebSocketSubscriber() throws Exception {
        long roomId = createFixture();
        WebSocketStompClient client = newClient();
        StompSession b1 = connect(client, issueToken(2L));
        ArrayBlockingQueue<Map<String, Object>> framesB = new ArrayBlockingQueue<>(4);

        try {
            subscribeMessages(b1, framesB, "2", 1);
            transactionTemplate.executeWithoutResult(status -> {
                chatMessageService.sendText(roomId, 11L,
                        new ChatMessageCreateRequest("rollback-message", "rolled back"));
                status.setRollbackOnly();
            });

            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from chat_messages where room_id = ?", Integer.class, roomId))
                    .isZero();
            assertNoEvent(drainFrames(framesB, 1000), "CHAT_MESSAGE_CREATED");
        } finally {
            disconnectIfConnected(b1);
            client.stop();
        }
    }

    private StompSession connect(WebSocketStompClient client, String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        return client.connectAsync(
                        "ws://localhost:" + port + ChatWebSocketDestinations.ENDPOINT,
                        new WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {
                            @Override
                            public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
                                return Map.class;
                            }

                            @Override
                            public void handleException(
                                    StompSession session,
                                    org.springframework.messaging.simp.stomp.StompCommand command,
                                    StompHeaders headers,
                                    byte[] payload,
                                    Throwable exception
                            ) {
                                sessionErrors.offer("exception=" + exception.getClass().getSimpleName());
                            }

                            @Override
                            public void handleTransportError(StompSession session, Throwable exception) {
                                sessionErrors.offer("transport=" + exception.getClass().getSimpleName());
                            }

                            @Override
                            @SuppressWarnings("unchecked")
                            public void handleFrame(StompHeaders headers, Object payload) {
                                if (payload instanceof Map<?, ?>) {
                                    protocolErrors.offer((Map<String, Object>) payload);
                                }
                            }

                        }
                )
                .get(15, TimeUnit.SECONDS);
    }

    private WebSocketStompClient newClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        return client;
    }

    private void sendText(StompSession session, long roomId, String clientMessageId, String body) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/chat/direct/rooms/" + roomId + "/messages");
        headers.setContentType(MediaType.APPLICATION_JSON);
        session.send(headers, new ChatMessageCreateRequest(clientMessageId, body));
    }

    private void assertError(
            ArrayBlockingQueue<ChatWebSocketErrorPayload> errors,
            String expectedCode
    ) throws InterruptedException {
        ChatWebSocketErrorPayload error = pollError(errors, expectedCode);
    }

    private ChatWebSocketErrorPayload pollError(
            ArrayBlockingQueue<ChatWebSocketErrorPayload> errors,
            String expectedCode
    ) throws InterruptedException {
        ChatWebSocketErrorPayload error = errors.poll(10, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        assertThat(error.eventType()).isEqualTo(ChatWebSocketEventType.CHAT_ERROR);
        assertThat(error.code()).isEqualTo(expectedCode);
        return error;
    }

    private void disconnectIfConnected(StompSession session) {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    private <T> void subscribe(
            StompSession session,
            String destination,
            Class<T> payloadType,
            ArrayBlockingQueue<T> queue
    ) {
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
                return payloadType;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.offer(payloadType.cast(payload));
            }
        });
    }

    private void subscribeAck(
            StompSession session,
            ArrayBlockingQueue<ChatSendAck> queue
    ) {
        subscribe(session, ChatWebSocketDestinations.USER_CHAT_MESSAGES, ChatSendAck.class,
                new ArrayBlockingQueueAdapter<>(queue, ack -> "CHAT_SEND_ACK".equals(ack.eventType())));
    }

    @SuppressWarnings("unchecked")
    private void subscribeMessages(
            StompSession session,
            ArrayBlockingQueue<Map<String, Object>> queue,
            String userId,
            int expectedSubscriptionCount
    ) {
        StompHeaders subscribeHeaders = new StompHeaders();
        subscribeHeaders.setDestination(ChatWebSocketDestinations.USER_CHAT_MESSAGES);
        session.subscribe(subscribeHeaders, new StompFrameHandler() {
            @Override
            public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.offer((Map<String, Object>) payload);
            }
        });
        awaitSubscription(userId, ChatWebSocketDestinations.USER_CHAT_MESSAGES, expectedSubscriptionCount);
    }

    private List<Map<String, Object>> collectUntil(
            ArrayBlockingQueue<Map<String, Object>> queue,
            Set<String> requiredEventTypes,
            long timeoutSeconds
    ) throws InterruptedException {
        List<Map<String, Object>> frames = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline && !containsAllEventTypes(frames, requiredEventTypes)) {
            long remaining = deadline - System.nanoTime();
            Map<String, Object> frame = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (frame == null) {
                break;
            }
            frames.add(frame);
        }
        return frames;
    }

    private List<Map<String, Object>> drainFrames(
            ArrayBlockingQueue<Map<String, Object>> queue,
            long timeoutMillis
    ) throws InterruptedException {
        List<Map<String, Object>> frames = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            long remaining = deadline - System.nanoTime();
            Map<String, Object> frame = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (frame == null) {
                break;
            }
            frames.add(frame);
        }
        return frames;
    }

    private boolean containsAllEventTypes(
            List<Map<String, Object>> frames,
            Set<String> requiredEventTypes
    ) {
        Set<String> actualEventTypes = frames.stream()
                .map(frame -> (String) frame.get("eventType"))
                .collect(java.util.stream.Collectors.toSet());
        return actualEventTypes.containsAll(requiredEventTypes);
    }

    private Map<String, Object> findFrame(List<Map<String, Object>> frames, String eventType) {
        return frames.stream()
                .filter(frame -> eventType.equals(frame.get("eventType")))
                .findFirst()
                .orElse(null);
    }

    private void assertAck(
            Map<String, Object> frame,
            long roomId,
            long messageId,
            String clientMessageId,
            boolean replayed
    ) {
        assertThat(frame).isNotNull();
        assertThat(frame.get("eventType")).isEqualTo("CHAT_SEND_ACK");
        assertThat(((Number) frame.get("roomId")).longValue()).isEqualTo(roomId);
        assertThat(((Number) frame.get("messageId")).longValue()).isEqualTo(messageId);
        assertThat(frame.get("clientMessageId")).isEqualTo(clientMessageId);
        assertThat(frame.get("replayed")).isEqualTo(replayed);
    }

    @SuppressWarnings("unchecked")
    private void assertCreated(
            Map<String, Object> frame,
            long roomId,
            long messageId,
            long senderPetId,
            String clientMessageId,
            String diagnostic
    ) {
        assertThat(frame).as(diagnostic).isNotNull();
        assertThat(frame.get("eventType")).isEqualTo("CHAT_MESSAGE_CREATED");
        assertThat(frame.get("roomType")).isEqualTo("DIRECT");
        Map<String, Object> message = (Map<String, Object>) frame.get("message");
        assertThat(((Number) message.get("messageId")).longValue()).isEqualTo(messageId);
        assertThat(((Number) message.get("roomId")).longValue()).isEqualTo(roomId);
        assertThat(((Number) message.get("senderPetId")).longValue()).isEqualTo(senderPetId);
        assertThat(message.get("clientMessageId")).isEqualTo(clientMessageId);
    }

    private void assertNoEvent(List<Map<String, Object>> frames, String eventType) {
        assertThat(frames).noneMatch(frame -> eventType.equals(frame.get("eventType")));
    }

    private void subscribeErrors(
            StompSession session,
            ArrayBlockingQueue<ChatWebSocketErrorPayload> queue,
            String userId,
            int expectedSubscriptionCount
    ) {
        StompHeaders subscribeHeaders = new StompHeaders();
        subscribeHeaders.setDestination(ChatWebSocketDestinations.USER_CHAT_ERRORS);
        session.subscribe(subscribeHeaders, new StompFrameHandler() {
            @Override
            public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
                return ChatWebSocketErrorPayload.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.offer((ChatWebSocketErrorPayload) payload);
            }
        });
        awaitSubscription(userId, ChatWebSocketDestinations.USER_CHAT_ERRORS, expectedSubscriptionCount);
    }

    private void awaitSubscription(String userId, String destination, int expectedSubscriptionCount) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            SimpUser user = userRegistry.getUser(userId);
            assertThat(user).isNotNull();
            long subscriptionCount = user.getSessions().stream()
                    .flatMap(session -> session.getSubscriptions().stream())
                    .filter(subscription -> destination.equals(subscription.getDestination()))
                    .count();
            assertThat(subscriptionCount).isGreaterThanOrEqualTo(expectedSubscriptionCount);
        });
    }

    private static final class ArrayBlockingQueueAdapter<T> extends ArrayBlockingQueue<T> {

        private final java.util.function.Predicate<T> predicate;
        private final ArrayBlockingQueue<T> delegate;

        private ArrayBlockingQueueAdapter(
                ArrayBlockingQueue<T> delegate,
                java.util.function.Predicate<T> predicate
        ) {
            super(1);
            this.delegate = delegate;
            this.predicate = predicate;
        }

        @Override
        public boolean offer(T value) {
            return predicate.test(value) && delegate.offer(value);
        }
    }

    private long createFixture() {
        insertUser(1L, "a@example.com", "a#A001");
        insertUser(2L, "b@example.com", "b#B001");
        insertPet(11L, 1L, "dog-a#A001");
        insertPet(22L, 2L, "dog-b#B001");
        jdbcTemplate.update("update users set active_pet_id = ? where id = ?", 11L, 1L);
        jdbcTemplate.update("update users set active_pet_id = ? where id = ?", 22L, 2L);
        Long roomId = jdbcTemplate.queryForObject(
                "insert into chat_rooms (type, status, origin, pet_low_id, pet_high_id) values ('DIRECT', 'ACTIVE', 'GREETING', 11, 22) returning id",
                Long.class
        );
        jdbcTemplate.update("insert into chat_room_participants (room_id, pet_id) values (?, ?), (?, ?)",
                roomId, 11L, roomId, 22L);
        return roomId;
    }

    private void insertUser(long id, String email, String publicTag) {
        jdbcTemplate.update("""
                insert into users (id, email, password_hash, nickname, public_tag, role,
                                   account_status, neighborhood_code)
                values (?, ?, 'encoded', ?, ?, 'USER', 'ACTIVE', '4113111500')
                """, id, email, "user" + id, publicTag);
    }

    private void insertPet(long id, long ownerId, String publicTag) {
        jdbcTemplate.update("""
                insert into pets (id, owner_user_id, public_tag, nickname, status)
                values (?, ?, ?, ?, 'ACTIVE')
                """, id, ownerId, publicTag, "dog" + id);
    }

    private void insertBlock(long blockerUserId, long blockedUserId, long sourcePetId, long targetPetId) {
        jdbcTemplate.update("""
                insert into user_blocks (blocker_user_id, blocked_user_id, source_pet_id, target_pet_id)
                values (?, ?, ?, ?)
                """, blockerUserId, blockedUserId, sourcePetId, targetPetId);
    }

    private void insertGreeting(long roomId) {
        long mediaId = jdbcTemplate.queryForObject("""
                insert into media (media_type, path, status, user_id)
                values ('IMAGE', ?, 'COMPLETED', 1)
                returning id
                """, Long.class, "greeting-media-" + roomId);
        long setlogId = jdbcTemplate.queryForObject("""
                insert into setlogs (author_pet_id, media_id, status, is_seed)
                values (11, ?, 'VISIBLE', true)
                returning id
                """, Long.class, mediaId);
        jdbcTemplate.update("""
                insert into greetings (from_pet_id, to_pet_id, setlog_id, room_id, expires_at)
                values (11, 22, ?, ?, ?)
                """, setlogId, roomId,
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(3600)));
    }

    private String issueToken(long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return tokenProvider.issueTokens(user).accessToken();
    }

    /**
     * Signed exactly the way {@code TokenProvider} signs, but with a TTL short enough to observe.
     * Overriding {@code app.jwt.access-ttl} instead would fork a second Testcontainers context
     * for a single assertion.
     */
    private String issueTokenExpiringAt(long userId, java.time.Instant expiresAt) {
        return io.jsonwebtoken.Jwts.builder()
                .subject(Long.toString(userId))
                .issuer(jwtProperties.issuer())
                .claim("tokenType", itda.common.constants.TokenType.ACCESS_TOKEN.name())
                .issuedAt(java.util.Date.from(java.time.Instant.now()))
                .expiration(java.util.Date.from(expiresAt))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        jwtProperties.secret().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();
    }
}
