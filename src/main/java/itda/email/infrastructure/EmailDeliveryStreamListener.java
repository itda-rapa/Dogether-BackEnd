package itda.email.infrastructure;

import itda.email.EmailDeliveryPublisher;
import itda.email.EmailVerificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class EmailDeliveryStreamListener implements StreamListener<String, MapRecord<String, String, String>> {
    private final StringRedisTemplate redisTemplate;
    private final VerificationEmailSender sender;
    private final EmailVerificationProperties properties;

    public EmailDeliveryStreamListener(@Qualifier("emailStringRedisTemplate") StringRedisTemplate redisTemplate,
                                       VerificationEmailSender sender,
                                       EmailVerificationProperties properties) {
        this.redisTemplate = redisTemplate;
        this.sender = sender;
        this.properties = properties;
    }

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        try {
            String payloadKey = record.getValue().get("payloadKey");
            EmailDeliveryPublisher.DeliveryPayload payload = deserialize(payloadKey);
            sender.send(payload.email(), payload.purpose(), payload.code());
            Long acknowledged = redisTemplate.opsForStream().acknowledge(
                    properties.streamKey(), properties.streamGroup(), record.getId());
            if (!Long.valueOf(1L).equals(acknowledged)) {
                throw new IllegalStateException("이메일 Stream ACK에 실패했습니다.");
            }
            redisTemplate.delete(payloadKey);
        } catch (RuntimeException exception) {
            log.error("Email delivery failed; stream entry remains pending. recordId={}, payloadKey={}",
                    record.getId().getValue(), record.getValue().get("payloadKey"), exception);
        }
    }

    private EmailDeliveryPublisher.DeliveryPayload deserialize(String payloadKey) {
        String serializedPayload = redisTemplate.opsForValue().get(payloadKey);
        if (serializedPayload == null || serializedPayload.isBlank()) {
            throw new IllegalStateException("이메일 delivery payload가 없습니다.");
        }
        return EmailDeliveryPublisher.DeliveryPayload.fromJson(serializedPayload);
    }
}
