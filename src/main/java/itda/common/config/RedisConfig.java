package itda.common.config;

import itda.common.properties.RedisProperties;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@RequiredArgsConstructor
public class RedisConfig {
    private final RedisProperties redisProperties;
    /** Email verification state is isolated in logical database 1. */
    @Bean(name = "emailRedisConnectionFactory")
    public RedisConnectionFactory emailRedisConnectionFactory() {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(
                        redisProperties.host(),
                        Integer.parseInt(redisProperties.port())
        );
        config.setDatabase(1);
        return new LettuceConnectionFactory(config);
    }

    @Bean(name = "emailStringRedisTemplate")
    public StringRedisTemplate emailStringRedisTemplate(
            @Qualifier("emailRedisConnectionFactory")
            RedisConnectionFactory emailRedisConnectionFactory
    ) {
        return new StringRedisTemplate(emailRedisConnectionFactory);
    }

    /**
     * Adding the DB-1 factory makes Redisson's missing-factory condition back off. Preserve the
     * pre-existing default Redisson factory for non-email, unqualified Redis infrastructure.
     */
    @Bean(name = "redissonConnectionFactory")
    @Primary
    @Profile("!test")
    public RedisConnectionFactory redissonConnectionFactory(RedissonClient redissonClient) {
        return new RedissonConnectionFactory(redissonClient);
    }

    /** Preserves the conventional default template while email uses its qualified template. */
    @Bean(name = "stringRedisTemplate")
    @Profile("!test")
    public StringRedisTemplate stringRedisTemplate(
            @Qualifier("redissonConnectionFactory") RedisConnectionFactory redissonConnectionFactory
    ) {
        return new StringRedisTemplate(redissonConnectionFactory);
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

    @Bean(name = "chatAuthorizationRedisConnectionFactory")
    public RedisConnectionFactory chatAuthorizationRedisConnectionFactory() {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(
                        redisProperties.host(),
                        Integer.parseInt(redisProperties.port())
                );
        config.setDatabase(3);
        return new LettuceConnectionFactory(config);
    }

    @Bean(name = "chatAuthorizationStringRedisTemplate")
    public StringRedisTemplate chatAuthorizationStringRedisTemplate(
            @Qualifier("chatAuthorizationRedisConnectionFactory")
            RedisConnectionFactory chatAuthorizationRedisConnectionFactory
    ) {
        return new StringRedisTemplate(chatAuthorizationRedisConnectionFactory);
    }

    /** Spring Data Redis repositories resolve this conventional bean name in the test profile. */
    @Bean(name = "redisTemplate")
    @Profile("test")
    public RedisTemplate<Object, Object> testRedisTemplate(
            @Qualifier("chatAuthorizationRedisConnectionFactory")
            RedisConnectionFactory chatAuthorizationRedisConnectionFactory
    ) {
        RedisTemplate<Object, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(chatAuthorizationRedisConnectionFactory);
        return template;
    }
}
