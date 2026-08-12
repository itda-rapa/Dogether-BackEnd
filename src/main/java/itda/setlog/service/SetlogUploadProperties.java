package itda.setlog.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.setlog-upload")
public record SetlogUploadProperties(boolean requireVersionId) {
}
