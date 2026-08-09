package itda.email;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class EmailDeliveryPublisher {

    private final StringRedisTemplate redisTemplate;
    private final EmailVerificationProperties properties;

    public EmailDeliveryPublisher(
            @Qualifier("emailStringRedisTemplate") StringRedisTemplate redisTemplate,
            EmailVerificationProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void publish(String normalizedEmail, EmailVerificationPurpose purpose, String code,
                        String challengeId) {
        String eventId = UUID.randomUUID().toString();
        String payloadKey = EmailRedisKeys.payload(eventId);
        String serializedPayload = DeliveryPayload.toJson(new DeliveryPayload(
                normalizedEmail, purpose, code, challengeId
        ));
        try {
            redisTemplate.opsForValue().set(payloadKey, serializedPayload, properties.payloadTtl());
            RecordId recordId = redisTemplate.opsForStream().add(properties.streamKey(), Map.of(
                    "eventId", eventId,
                    "payloadKey", payloadKey,
                    "createdAt", Instant.now().toString()
            ));
            if (recordId == null) {
                throw new IllegalStateException("이메일 Stream 발행에 실패했습니다.");
            }
        } catch (RuntimeException exception) {
            try {
                redisTemplate.delete(payloadKey);
            } catch (RuntimeException cleanupException) {
                log.error("Email delivery payload cleanup failed. payloadKey={}", payloadKey,
                        cleanupException);
            }
            throw exception;
        }
    }

    public record DeliveryPayload(
            String email,
            EmailVerificationPurpose purpose,
            String code,
            String challengeId
    ) {
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

        public static String toJson(DeliveryPayload payload) {
            try {
                return OBJECT_MAPPER.writeValueAsString(payload);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("이메일 delivery payload 직렬화에 실패했습니다.", exception);
            }
        }

        public static DeliveryPayload fromJson(String serializedPayload) {
            try {
                return OBJECT_MAPPER.readValue(serializedPayload, DeliveryPayload.class);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("이메일 delivery payload 역직렬화에 실패했습니다.", exception);
            }
        }
    }
}
