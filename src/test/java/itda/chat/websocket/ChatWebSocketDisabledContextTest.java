package itda.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "app.websocket.enabled=false")
class ChatWebSocketDisabledContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void disabledFlagOmitsWebSocketConfigurationBrokerAndListener() {
        assertThat(applicationContext.getBeansOfType(ChatWebSocketConfig.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(ChatMessageRealtimeListener.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(SimpMessagingTemplate.class)).isEmpty();
    }
}
