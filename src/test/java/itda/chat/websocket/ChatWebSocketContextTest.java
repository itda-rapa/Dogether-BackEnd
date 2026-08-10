package itda.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@SpringBootTest(properties = "app.websocket.enabled=true")
class ChatWebSocketContextTest {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private ChatMessageRealtimeListener realtimeListener;

    @Test
    void enabledWebSocketContextProvidesBrokerAndAfterCommitListener() {
        assertThat(messagingTemplate).isNotNull();
        assertThat(realtimeListener).isNotNull();
    }
}
