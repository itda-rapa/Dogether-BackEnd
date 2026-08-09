package itda.email.infrastructure;

import itda.email.EmailVerificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.email-verification", name = "worker-enabled", havingValue = "true")
public class EmailDeliveryStreamInitializer implements ApplicationRunner {
    private final StringRedisTemplate redisTemplate;
    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private final EmailDeliveryStreamListener listener;
    private final EmailVerificationProperties properties;

    public EmailDeliveryStreamInitializer(@Qualifier("emailStringRedisTemplate") StringRedisTemplate redisTemplate,
                                          @Qualifier("emailDeliveryStreamContainer") StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
                                          EmailDeliveryStreamListener listener,
                                          EmailVerificationProperties properties) {
        this.redisTemplate = redisTemplate;
        this.container = container;
        this.listener = listener;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        createGroup();
        var request = StreamMessageListenerContainer.StreamReadRequest
                .builder(StreamOffset.create(properties.streamKey(), ReadOffset.lastConsumed()))
                .consumer(Consumer.from(properties.streamGroup(), properties.streamConsumer()))
                .autoAcknowledge(false)
                .cancelOnError(error -> false)
                .errorHandler(error -> log.error("Email Stream listener failure", error))
                .build();
        container.register(request, listener);
        container.start();
    }

    private void createGroup() {
        try {
            redisTemplate.opsForStream().createGroup(properties.streamKey(), ReadOffset.from("0-0"), properties.streamGroup());
        } catch (DataAccessException exception) {
            String message = exception.getMostSpecificCause().getMessage();
            if (message == null || !message.contains("BUSYGROUP")) {
                throw exception;
            }
        }
    }
}
