package itda.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import itda.common.properties.RedisProperties;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisConfigNonTestWiringTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
            .withBean(RedisProperties.class, () -> new RedisProperties("localhost", "6379"))
            .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
            .withUserConfiguration(RedisConfig.class);

    @Test
    void resolvesEmailAndDefaultRedisBeansSeparatelyOutsideTestProfile() {
        contextRunner.run(context -> {
            RedisConnectionFactory emailFactory = context.getBean(
                    "emailRedisConnectionFactory", RedisConnectionFactory.class
            );
            RedisConnectionFactory defaultFactory = context.getBean(
                    "redissonConnectionFactory", RedisConnectionFactory.class
            );
            StringRedisTemplate emailTemplate = context.getBean(
                    "emailStringRedisTemplate", StringRedisTemplate.class
            );
            StringRedisTemplate defaultTemplate = context.getBean(
                    "stringRedisTemplate", StringRedisTemplate.class
            );

            assertThat(emailFactory).isInstanceOf(LettuceConnectionFactory.class);
            assertThat(((LettuceConnectionFactory) emailFactory).getDatabase()).isEqualTo(1);
            assertThat(defaultFactory).isInstanceOf(RedissonConnectionFactory.class);
            assertThat(context.getBean(RedisConnectionFactory.class)).isSameAs(defaultFactory);
            assertThat(emailTemplate.getConnectionFactory()).isSameAs(emailFactory);
            assertThat(defaultTemplate.getConnectionFactory()).isSameAs(defaultFactory);
            assertThat(emailFactory).isNotSameAs(defaultFactory);
        });
    }
}
