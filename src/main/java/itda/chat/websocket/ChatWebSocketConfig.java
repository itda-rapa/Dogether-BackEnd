package itda.chat.websocket;

import itda.common.properties.CorsProperties;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSocketMessageBroker
@ConditionalOnProperty(prefix = "app.websocket", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ChatWebSocketProperties.class)
@RequiredArgsConstructor
public class ChatWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final ChatWebSocketProperties properties;
    private final CorsProperties corsProperties;
    private final ChatWebSocketChannelInterceptor channelInterceptor;
    private final ChatWebSocketSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes(ChatWebSocketDestinations.APPLICATION_PREFIX);
        registry.setUserDestinationPrefix(ChatWebSocketDestinations.USER_PREFIX);
        registry.enableSimpleBroker("/queue", "/topic")
                .setHeartbeatValue(new long[]{
                        properties.heartbeatSend().toMillis(),
                        properties.heartbeatReceive().toMillis()
                })
                .setTaskScheduler(webSocketTaskScheduler());
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        List<String> origins = corsProperties.allowedOrigins() == null
                ? List.of()
                : corsProperties.allowedOrigins();
        if (origins.contains("*")) {
            throw new IllegalStateException("Wildcard WebSocket Origin is not allowed");
        }
        registry.setErrorHandler(chatStompErrorHandler(objectMapper));
        registry.addEndpoint(ChatWebSocketDestinations.ENDPOINT)
                .setAllowedOrigins(origins.toArray(String[]::new));
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(channelInterceptor);
    }

    /**
     * The decorator is how the expiry scheduler gets a closable handle on a session — the inbound
     * channel only ever sees frames and session ids, never the session itself.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit((int) properties.messageSizeLimit().toBytes());
        registration.addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessionRegistry.bind(session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status)
                    throws Exception {
                sessionRegistry.forget(session.getId());
                super.afterConnectionClosed(session, status);
            }
        });
    }

    @Bean
    TaskScheduler webSocketTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("dogether-ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    ChatStompErrorHandler chatStompErrorHandler(ObjectMapper objectMapper) {
        return new ChatStompErrorHandler(objectMapper);
    }

}
