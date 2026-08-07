package itda.email.infrastructure;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

import itda.email.EmailVerificationProperties;
import itda.email.EmailVerificationPurpose;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class EmailDeliveryStreamListenerTest {
    private final EmailVerificationProperties properties = new EmailVerificationProperties(
            "test-email-verification-hmac-secret-at-least-32-bytes", Duration.ofMinutes(5),
            Duration.ofMinutes(15), Duration.ofSeconds(60), Duration.ofMinutes(5), false,
            "fake", "stream", "group", "consumer", 0, 5
    );

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private StreamOperations<String, Object, Object> streamOperations;
    @Mock private VerificationEmailSender sender;

    @Test
    void sendsAcknowledgesThenDeletesPayload() throws Exception {
        EmailDeliveryStreamListener listener = listener();
        MapRecord<String, String, String> record = record();
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(redisTemplate.opsForStream()).willReturn(streamOperations);
        given(valueOperations.get("payload-key")).willReturn(payloadJson());
        given(streamOperations.acknowledge("stream", "group", record.getId())).willReturn(1L);

        listener.onMessage(record);

        InOrder order = inOrder(sender, streamOperations, redisTemplate);
        order.verify(sender).send("user@example.com", EmailVerificationPurpose.SIGNUP, "123456");
        order.verify(streamOperations).acknowledge("stream", "group", record.getId());
        order.verify(redisTemplate).delete("payload-key");
    }

    @Test
    void leavesEntryPendingAndPayloadWhenSendingFails() throws Exception {
        EmailDeliveryStreamListener listener = listener();
        MapRecord<String, String, String> record = record();
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("payload-key")).willReturn(payloadJson());
        org.mockito.Mockito.doThrow(new IllegalStateException("SMTP unavailable"))
                .when(sender).send("user@example.com", EmailVerificationPurpose.SIGNUP, "123456");

        listener.onMessage(record);

        then(streamOperations).shouldHaveNoInteractions();
        then(redisTemplate).should(org.mockito.Mockito.never()).delete("payload-key");
    }

    @Test
    void keepsPayloadWhenAcknowledgeDidNotSucceed() throws Exception {
        EmailDeliveryStreamListener listener = listener();
        MapRecord<String, String, String> record = record();
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(redisTemplate.opsForStream()).willReturn(streamOperations);
        given(valueOperations.get("payload-key")).willReturn(payloadJson());
        given(streamOperations.acknowledge("stream", "group", record.getId())).willReturn(0L);

        listener.onMessage(record);

        then(redisTemplate).should(org.mockito.Mockito.never()).delete("payload-key");
    }

    @Test
    void leavesEntryPendingAndPayloadWhenPayloadIsMalformed() {
        EmailDeliveryStreamListener listener = listener();
        MapRecord<String, String, String> record = record();
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("payload-key")).willReturn("not-json");

        listener.onMessage(record);

        then(sender).shouldHaveNoInteractions();
        then(streamOperations).shouldHaveNoInteractions();
        then(redisTemplate).should(org.mockito.Mockito.never()).delete("payload-key");
    }

    private MapRecord<String, String, String> record() {
        return MapRecord.create("stream", Map.of("payloadKey", "payload-key"))
                .withId(RecordId.of("1-0"));
    }

    private EmailDeliveryStreamListener listener() {
        return new EmailDeliveryStreamListener(redisTemplate, sender, properties);
    }

    private String payloadJson() {
        return itda.email.EmailDeliveryPublisher.DeliveryPayload.toJson(
                new itda.email.EmailDeliveryPublisher.DeliveryPayload(
                "user@example.com", EmailVerificationPurpose.SIGNUP, "123456", "challenge-id"
                )
        );
    }
}
