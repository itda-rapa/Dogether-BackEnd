package itda.chat.websocket;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "app.websocket")
public record ChatWebSocketProperties(
        boolean enabled,
        Duration heartbeatSend,
        Duration heartbeatReceive,
        DataSize messageSizeLimit
) {

    public ChatWebSocketProperties {
        heartbeatSend = heartbeatSend == null ? Duration.ofSeconds(10) : heartbeatSend;
        heartbeatReceive = heartbeatReceive == null ? Duration.ofSeconds(10) : heartbeatReceive;
        messageSizeLimit = messageSizeLimit == null ? DataSize.ofKilobytes(32) : messageSizeLimit;
    }
}
