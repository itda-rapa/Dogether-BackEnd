package itda.route.service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class RouteRequestPublisher {

    public static final String TOPIC = "route-calculation-request-topic";
    private final KafkaTemplate<String, String> kafkaTemplate;

    public RouteRequestPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(UUID requestId) {
        try {
            kafkaTemplate.send(TOPIC, requestId.toString(), requestId.toString())
                    .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Route request publish interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Route request publish failed", exception);
        }
    }
}

