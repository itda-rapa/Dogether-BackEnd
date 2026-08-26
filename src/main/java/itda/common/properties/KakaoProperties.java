package itda.common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.kakao.local")
public record KakaoProperties(
   String baseUrl,
   String restApiKey
) {
}
