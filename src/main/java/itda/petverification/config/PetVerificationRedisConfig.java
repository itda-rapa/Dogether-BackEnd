package itda.petverification.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
public class PetVerificationRedisConfig {
    @Bean(name = "petVerificationIssueScript")
    DefaultRedisScript<Long> issueScript() { return script("redis/pet-verification-issue.lua"); }

    @Bean(name = "petVerificationReserveScript")
    DefaultRedisScript<List> reserveScript() { return listScript("redis/pet-verification-reserve.lua"); }

    @Bean(name = "petVerificationReleaseScript")
    DefaultRedisScript<Long> releaseScript() { return script("redis/pet-verification-release.lua"); }

    @Bean(name = "petVerificationFinalizeScript")
    DefaultRedisScript<Long> finalizeScript() { return script("redis/pet-verification-finalize.lua"); }

    private DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    private DefaultRedisScript<List> listScript(String path) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(List.class);
        return script;
    }
}
