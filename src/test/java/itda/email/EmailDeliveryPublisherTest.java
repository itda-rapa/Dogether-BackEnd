package itda.email;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class EmailDeliveryPublisherTest {
    private final EmailVerificationProperties properties = new EmailVerificationProperties(
            "test-email-verification-hmac-secret-at-least-32-bytes", Duration.ofMinutes(5),
            Duration.ofMinutes(15), Duration.ofSeconds(60), Duration.ofMinutes(5), false,
            "fake", "stream", "group", "consumer", 0, 5
    );

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private StreamOperations<String, Object, Object> streamOperations;
    private EmailDeliveryPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new EmailDeliveryPublisher(redisTemplate, properties);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(redisTemplate.opsForStream()).willReturn(streamOperations);
    }

    @Test
    void preservesXaddFailureAfterDeletingPayload() {
        IllegalStateException xaddFailure = new IllegalStateException("XADD failed");
        org.mockito.Mockito.doThrow(xaddFailure).when(streamOperations).add(anyString(), anyMap());

        assertThatThrownBy(() -> publisher.publish(
                "user@example.com", EmailVerificationPurpose.SIGNUP, "123456", "challenge-id"
        )).isSameAs(xaddFailure);

        then(valueOperations).should().set(anyString(), anyString(), org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(5)));
        then(redisTemplate).should().delete(anyString());
    }

    @Test
    void preservesXaddFailureWhenPayloadCleanupFails() {
        IllegalStateException xaddFailure = new IllegalStateException("XADD failed");
        org.mockito.Mockito.doThrow(xaddFailure).when(streamOperations).add(anyString(), anyMap());
        org.mockito.Mockito.doThrow(new IllegalStateException("cleanup failed"))
                .when(redisTemplate).delete(anyString());

        assertThatThrownBy(() -> publisher.publish(
                "user@example.com", EmailVerificationPurpose.SIGNUP, "123456", "challenge-id"
        )).isSameAs(xaddFailure);
    }
}
