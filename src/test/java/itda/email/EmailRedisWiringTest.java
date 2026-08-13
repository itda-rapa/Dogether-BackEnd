package itda.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import itda.common.config.RedisConfig;
import itda.common.properties.RedisProperties;
import org.redisson.api.RedissonClient;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class EmailRedisWiringTest {
    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final RedisConnectionFactory conventionalConnectionFactory;
    private final RedisTemplate<?, ?> conventionalRedisTemplate;
    private final RedisConnectionFactory petVerificationConnectionFactory;
    private final StringRedisTemplate petVerificationRedisTemplate;

    EmailRedisWiringTest(
            @Qualifier("emailRedisConnectionFactory") RedisConnectionFactory connectionFactory,
            @Qualifier("emailStringRedisTemplate") StringRedisTemplate redisTemplate,
            @Qualifier("redisConnectionFactory") RedisConnectionFactory conventionalConnectionFactory,
            @Qualifier("redisTemplate") RedisTemplate<?, ?> conventionalRedisTemplate,
            @Qualifier("petVerificationRedisConnectionFactory") RedisConnectionFactory petVerificationConnectionFactory,
            @Qualifier("petVerificationStringRedisTemplate") StringRedisTemplate petVerificationRedisTemplate
    ) {
        this.connectionFactory = connectionFactory;
        this.redisTemplate = redisTemplate;
        this.conventionalConnectionFactory = conventionalConnectionFactory;
        this.conventionalRedisTemplate = conventionalRedisTemplate;
        this.petVerificationConnectionFactory = petVerificationConnectionFactory;
        this.petVerificationRedisTemplate = petVerificationRedisTemplate;
    }

    @Test
    void emailRedisUsesLogicalDatabaseOneWithoutConnecting() {
        assertThat(connectionFactory).isInstanceOf(LettuceConnectionFactory.class);
        assertThat(((LettuceConnectionFactory) connectionFactory).getDatabase()).isEqualTo(1);
        assertThat(redisTemplate.getConnectionFactory()).isSameAs(connectionFactory);
    }

    @Test
    void testProfileExposesConventionalRedisTemplateWithoutReintroducingADbZeroFactory() {
        assertThat(conventionalConnectionFactory).isSameAs(connectionFactory);
        assertThat(conventionalRedisTemplate.getConnectionFactory()).isSameAs(connectionFactory);
        assertThat(((LettuceConnectionFactory) conventionalConnectionFactory).getDatabase()).isEqualTo(1);
        assertThat(((LettuceConnectionFactory) petVerificationConnectionFactory).getDatabase()).isEqualTo(5);
        assertThat(petVerificationRedisTemplate.getConnectionFactory()).isSameAs(petVerificationConnectionFactory);
        assertThat(petVerificationConnectionFactory).isNotSameAs(conventionalConnectionFactory);
    }

    @Test
    void nonTestFactoriesKeepRedissonAsDefaultAndEmailOnDatabaseOne() {
        RedisConfig config = new RedisConfig(new RedisProperties("localhost", "6379"));
        RedisConnectionFactory emailFactory = config.emailRedisConnectionFactory();
        RedisConnectionFactory defaultFactory = config.redissonConnectionFactory(mock(RedissonClient.class));
        StringRedisTemplate defaultTemplate = config.stringRedisTemplate(defaultFactory);

        assertThat(emailFactory).isInstanceOf(LettuceConnectionFactory.class);
        assertThat(((LettuceConnectionFactory) emailFactory).getDatabase()).isEqualTo(1);
        assertThat(defaultFactory).isInstanceOf(RedissonConnectionFactory.class);
        assertThat(defaultTemplate.getConnectionFactory()).isSameAs(defaultFactory);
        assertThat(emailFactory).isNotSameAs(defaultFactory);
    }
}
