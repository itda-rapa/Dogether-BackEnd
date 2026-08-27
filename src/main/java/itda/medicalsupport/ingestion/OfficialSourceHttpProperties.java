package itda.medicalsupport.ingestion;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.medical-support.http")
public record OfficialSourceHttpProperties(Duration connectTimeout, Duration readTimeout) { }
