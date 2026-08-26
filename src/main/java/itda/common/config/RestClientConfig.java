package itda.common.config;

import itda.common.properties.KakaoProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final KakaoProperties kakaoProperties;

    @Bean
    public RestClient kakaoRestClient() {
        return RestClient.builder()
                .baseUrl(kakaoProperties.baseUrl())
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "KakaoAK " + kakaoProperties.restApiKey()
                ).build();
    }
}
