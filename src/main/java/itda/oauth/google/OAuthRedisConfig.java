package itda.oauth.google;

import itda.common.properties.RedisProperties;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
class OAuthRedisConfig {

    /** OAuth browser transactions are isolated from other short-lived Redis artifacts. */
    @Bean(name = "oauthRedisConnectionFactory")
    RedisConnectionFactory oauthRedisConnectionFactory(RedisProperties properties) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                properties.host(), Integer.parseInt(properties.port())
        );
        configuration.setDatabase(6);
        return new LettuceConnectionFactory(configuration);
    }

    @Bean(name = "oauthStringRedisTemplate")
    StringRedisTemplate oauthStringRedisTemplate(
            @Qualifier("oauthRedisConnectionFactory") RedisConnectionFactory connectionFactory
    ) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean(name = "oauthTransactionIssueScript")
    DefaultRedisScript<Long> oauthTransactionIssueScript() {
        return script("redis/oauth-transaction-issue.lua", Long.class);
    }

    @Bean(name = "oauthTransactionConsumeScript")
    DefaultRedisScript<List> oauthTransactionConsumeScript() {
        return script("redis/oauth-transaction-consume.lua", List.class);
    }

    private <T> DefaultRedisScript<T> script(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(resultType);
        return script;
    }
}
