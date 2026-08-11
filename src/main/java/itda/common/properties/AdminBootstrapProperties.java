package itda.common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.bootstrap-admin")
public record AdminBootstrapProperties(
        boolean enabled,
        String email,
        String password,
        String nickname,
        String neighborhoodCode
) {
}
