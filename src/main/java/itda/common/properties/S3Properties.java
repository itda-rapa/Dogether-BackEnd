package itda.common.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.s3")
@Validated
public record S3Properties(
        @NotBlank String bucket,
        @NotBlank String region,
        String endpoint,    // 백엔드가 저장소에 접근할 주소
        String presignEndpoint,    // 프론트엔드가 접근할 Presigned URL 주소
        boolean pathStyleAccessEnabled
) {
}
