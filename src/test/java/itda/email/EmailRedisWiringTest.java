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
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class EmailRedisWiringTest {
    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;

    EmailRedisWiringTest(
            @Qualifier("emailRedisConnectionFactory") RedisConnectionFactory connectionFactory,
            @Qualifier("emailStringRedisTemplate") StringRedisTemplate redisTemplate
    ) {
        this.connectionFactory = connectionFactory;
        this.redisTemplate = redisTemplate;
    }

    @Test
    void emailRedisUsesLogicalDatabaseOneWithoutConnecting() {
        assertThat(connectionFactory).isInstanceOf(LettuceConnectionFactory.class);
        assertThat(((LettuceConnectionFactory) connectionFactory).getDatabase()).isEqualTo(1);
        assertThat(redisTemplate.getConnectionFactory()).isSameAs(connectionFactory);
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
