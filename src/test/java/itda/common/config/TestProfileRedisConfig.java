package itda.common.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Test-only conventional Redis candidate. Production continues to use Redisson as its default;
 * tests intentionally reuse the existing DB-1 email factory instead of creating a DB-0 pair.
 */
@Configuration(proxyBeanMethods = false)
@Profile("test")
class TestProfileRedisConfig {

    @Bean(name = "redisConnectionFactory")
    @Primary
    RedisConnectionFactory conventionalTestRedisConnectionFactory(
            @Qualifier("emailRedisConnectionFactory") RedisConnectionFactory emailRedisConnectionFactory
    ) {
        return emailRedisConnectionFactory;
    }

    @Bean(name = "stringRedisTemplate")
    StringRedisTemplate conventionalTestStringRedisTemplate(
            @Qualifier("redisConnectionFactory") RedisConnectionFactory redisConnectionFactory
    ) {
        return new StringRedisTemplate(redisConnectionFactory);
    }
}
