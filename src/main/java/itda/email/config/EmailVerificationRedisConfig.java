package itda.email.config;

import java.time.Duration;
import java.util.List;
import itda.email.EmailVerificationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

@Configuration
public class EmailVerificationRedisConfig {

    @Bean(name = "emailVerificationIssueScript")
    DefaultRedisScript<List> emailVerificationIssueScript() {
        return script("redis/email-verification-issue.lua");
    }

    @Bean(name = "emailVerificationConfirmScript")
    DefaultRedisScript<List> emailVerificationConfirmScript() {
        return script("redis/email-verification-confirm.lua");
    }

    @Bean(name = "emailVerificationConsumeScript")
    DefaultRedisScript<Long> emailVerificationConsumeScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/email-verification-consume.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean(name = "emailDeliveryStreamContainer")
    StreamMessageListenerContainer<String, MapRecord<String, String, String>> emailDeliveryStreamContainer(
            @Qualifier("emailRedisConnectionFactory") RedisConnectionFactory connectionFactory
    ) {
        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .pollTimeout(Duration.ofSeconds(1))
                .batchSize(10)
                .autoStartup(false)
                .build();
        return StreamMessageListenerContainer.create(connectionFactory, options);
    }

    private DefaultRedisScript<List> script(String location) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(List.class);
        return script;
    }
}
