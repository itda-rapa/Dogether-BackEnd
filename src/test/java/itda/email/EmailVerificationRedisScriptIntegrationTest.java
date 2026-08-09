package itda.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.domain.Range;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("redis")
@Testcontainers
class EmailVerificationRedisScriptIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory factory;

    @AfterEach
    void tearDown() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void issueConfirmAndConsumePreserveBindingAndSingleUse() throws Exception {
        StringRedisTemplate template = template();
        String challengeId = "challenge-1";
        String emailHmac = "email-hmac";
        String challengeKey = EmailRedisKeys.challenge(challengeId);
        String currentKey = EmailRedisKeys.current(EmailVerificationPurpose.SIGNUP, emailHmac);
        String cooldownKey = EmailRedisKeys.cooldown(EmailVerificationPurpose.SIGNUP, emailHmac);
        List<?> issued = template.execute(listScript("redis/email-verification-issue.lua"),
                List.of(challengeKey, currentKey, cooldownKey), "ISSUE", challengeId, emailHmac,
                "SIGNUP", "code-hmac", "300", "60", "dogether:email:v1:challenge:", "5");
        assertThat(((Number) issued.getFirst()).longValue()).isEqualTo(1);
        assertThat(template.getExpire(challengeKey)).isPositive();

        String tokenKey = EmailRedisKeys.token("token-hmac");
        List<?> confirmed = template.execute(listScript("redis/email-verification-confirm.lua"),
                List.of(challengeKey, tokenKey), "code-hmac", challengeId, "900", "", "",
                EmailRedisKeys.currentPrefix());
        assertThat(((Number) confirmed.getFirst()).longValue()).isEqualTo(1);
        assertThat(template.hasKey(challengeKey)).isFalse();
        assertThat(template.hasKey(currentKey)).isFalse();
        assertThat(template.getExpire(tokenKey)).isPositive();

        DefaultRedisScript<Long> consume = longScript("redis/email-verification-consume.lua");
        assertThat(template.execute(consume, List.of(tokenKey), "wrong-email", "SIGNUP")).isZero();
        assertThat(template.hasKey(tokenKey)).isTrue();
        assertThat(template.execute(consume, List.of(tokenKey), emailHmac, "PASSWORD_RESET")).isZero();
        assertThat(template.hasKey(tokenKey)).isTrue();

        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Long> consumeTask = () -> template.execute(consume, List.of(tokenKey), emailHmac, "SIGNUP");
            var results = executor.invokeAll(List.of(consumeTask, consumeTask));
            long successes = results.stream().mapToLong(result -> {
                try { return result.get(); } catch (Exception exception) { throw new AssertionError(exception); }
            }).sum();
            assertThat(successes).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedAttemptsClearOnlyMatchingCurrentPointerAndCollisionKeepsChallenge() {
        StringRedisTemplate template = template();
        String challengeId = "challenge-2";
        String emailHmac = "email-hmac-2";
        String challengeKey = EmailRedisKeys.challenge(challengeId);
        String currentKey = EmailRedisKeys.current(EmailVerificationPurpose.SIGNUP, emailHmac);
        String cooldownKey = EmailRedisKeys.cooldown(EmailVerificationPurpose.SIGNUP, emailHmac);
        template.execute(listScript("redis/email-verification-issue.lua"), List.of(challengeKey, currentKey, cooldownKey),
                "ISSUE", challengeId, emailHmac, "SIGNUP", "code-hmac", "300", "60",
                "dogether:email:v1:challenge:", "2");
        String tokenKey = EmailRedisKeys.token("collision");
        template.opsForHash().put(tokenKey, "emailHmac", "existing");
        template.expire(tokenKey, Duration.ofMinutes(15));
        List<?> collision = template.execute(listScript("redis/email-verification-confirm.lua"),
                List.of(challengeKey, tokenKey), "code-hmac", challengeId, "900", "", "",
                EmailRedisKeys.currentPrefix());
        assertThat(((Number) collision.getFirst()).longValue()).isEqualTo(-3);
        assertThat(template.hasKey(challengeKey)).isTrue();
        assertThat(template.opsForValue().get(currentKey)).isEqualTo(challengeId);

        template.execute(listScript("redis/email-verification-confirm.lua"), List.of(challengeKey, tokenKey),
                "wrong", challengeId, "900", "", "", EmailRedisKeys.currentPrefix());
        List<?> exhausted = template.execute(listScript("redis/email-verification-confirm.lua"), List.of(challengeKey, tokenKey),
                "wrong", challengeId, "900", "", "", EmailRedisKeys.currentPrefix());
        assertThat(((Number) exhausted.getFirst()).longValue()).isEqualTo(-2);
        assertThat(template.hasKey(challengeKey)).isFalse();
        assertThat(template.hasKey(currentKey)).isFalse();
    }

    @Test
    void deliveryPayloadIsStoredAsJsonWithAnImmediateTtl() throws Exception {
        StringRedisTemplate template = template();
        EmailVerificationProperties properties = new EmailVerificationProperties(
                "test-email-verification-hmac-secret-at-least-32-bytes", Duration.ofMinutes(5),
                Duration.ofMinutes(15), Duration.ofSeconds(60), Duration.ofMinutes(5), false,
                "fake", "delivery-stream", "group", "consumer", 5
        );
        EmailDeliveryPublisher publisher = new EmailDeliveryPublisher(template, properties);

        publisher.publish("user@example.com", EmailVerificationPurpose.SIGNUP, "123456", "challenge-id");

        var records = template.opsForStream().range(properties.streamKey(), Range.unbounded());
        String payloadKey = (String) records.getFirst().getValue().get("payloadKey");
        String serializedPayload = template.opsForValue().get(payloadKey);
        EmailDeliveryPublisher.DeliveryPayload payload = new ObjectMapper().readValue(
                serializedPayload, EmailDeliveryPublisher.DeliveryPayload.class
        );
        assertThat(serializedPayload).isNotBlank();
        assertThat(template.getExpire(payloadKey)).isPositive();
        assertThat(payload.email()).isEqualTo("user@example.com");
        assertThat(payload.code()).isEqualTo("123456");
    }

    @Test
    void acknowledgedStreamEntryIsExplicitlyRemoved() {
        StringRedisTemplate template = template();
        String streamKey = "delivery-cleanup-stream";
        String group = "delivery-cleanup-group";
        RecordId recordId = template.opsForStream().add(streamKey, Map.of("payloadKey", "payload-key"));
        template.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), group);

        var delivered = template.opsForStream().read(
                Consumer.from(group, "delivery-cleanup-consumer"),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed())
        );

        assertThat(delivered).hasSize(1);
        assertThat(delivered.getFirst().getId()).isEqualTo(recordId);
        assertThat(template.opsForStream().acknowledge(streamKey, group, recordId)).isEqualTo(1L);
        assertThat(template.opsForStream().delete(streamKey, recordId)).isEqualTo(1L);
        assertThat(template.opsForStream().range(streamKey, Range.unbounded())).isEmpty();
    }

    private StringRedisTemplate template() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        configuration.setDatabase(1);
        factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    private DefaultRedisScript<List> listScript(String path) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(List.class);
        return script;
    }

    private DefaultRedisScript<Long> longScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
