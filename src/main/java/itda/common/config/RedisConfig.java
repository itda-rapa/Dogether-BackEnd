package itda.common.config;

import itda.common.properties.RedisProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

@Configuration
@RequiredArgsConstructor
public class RedisConfig {
    private final RedisProperties redisProperties;
    // 이메일 인증 전용
    @Bean
    @Qualifier("email")
    public RedisTemplate<String, String> emailRedisTemplate(){
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(
                        redisProperties.host(),
                        Integer.parseInt(redisProperties.port())
                );
        config.setDatabase(1);

        RedisTemplate<String, String> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(new LettuceConnectionFactory(config));
        return redisTemplate;
    }
    // 캐싱 전용
    @Bean
    @Qualifier("cache")
    public RedisTemplate<String, String> cachingRedisTemplate(){
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(
                        redisProperties.host(),
                        Integer.parseInt(redisProperties.port())
                );
        config.setDatabase(2);

        RedisTemplate<String, String> redisTemplate =new RedisTemplate<>();
        redisTemplate.setConnectionFactory(new LettuceConnectionFactory(config));
        return redisTemplate;
    }
    // 분산락 전용
    @Bean
    @Qualifier("lock")
    public RedisTemplate<String, String> lockingRedisTemplate(){
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(
                        redisProperties.host(),
                        Integer.parseInt(redisProperties.port())
                );
        config.setDatabase(3);

        RedisTemplate<String, String> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(new LettuceConnectionFactory(config));
        return redisTemplate;
    }
    // 멱등성 전용
    @Bean
    @Qualifier("idempotent")
    public RedisTemplate<String, String> idempotentRedisTemplate(){
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(
                        redisProperties.host(),
                        Integer.parseInt(redisProperties.port())
                );
        config.setDatabase(4);

        RedisTemplate<String, String> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(new LettuceConnectionFactory(config));
        return redisTemplate;
    }
}
