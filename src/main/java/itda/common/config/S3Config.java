package itda.common.config;

import itda.common.properties.S3Properties;
import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.S3Presigner.Builder;

@Configuration
public class S3Config {

    @Bean
    S3Client s3Client(S3Properties properties) {    // 백엔드 서버가 S3호환 스토리지에 직접 요청을 보낼 때 사용하는 AWS SDK 설정. 백엔드가 HEAD, DELETE 요청을 보낼 때 사용
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyleAccessEnabled())
                        .build());

        if (StringUtils.hasText(properties.endpoint())) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }

        return builder.build();
    }

    @Bean
    S3Presigner s3Presigner(S3Properties properties) {  // 사용자에게 전달할 PUT/GET URL 생성
        Builder builder = S3Presigner.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyleAccessEnabled())
                        .build());

        String presignEndpoint = StringUtils.hasText(properties.presignEndpoint())
                ? properties.presignEndpoint()
                : properties.endpoint();


        if (StringUtils.hasText(presignEndpoint)) {
            builder.endpointOverride(URI.create(presignEndpoint));
        }

        return builder.build();
    }
}
