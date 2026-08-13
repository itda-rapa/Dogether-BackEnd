package itda.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "WEBSOCKET_ENABLED=true")
@ActiveProfiles({"prod", "test"})
class ChatWebSocketProductionContextTest {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Test
    void prodProfileEnablesWebSocketWhenTheEnvironmentFlagIsTrue() {
        assertThat(messagingTemplate).isNotNull();
    }
}
