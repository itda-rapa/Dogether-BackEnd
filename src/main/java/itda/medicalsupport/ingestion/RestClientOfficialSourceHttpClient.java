package itda.medicalsupport.ingestion;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
@Component
class RestClientOfficialSourceHttpClient implements OfficialSourceHttpClient {
 private final RestClient restClient;
 RestClientOfficialSourceHttpClient(OfficialSourceHttpProperties properties) {
  SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
  factory.setConnectTimeout(properties.connectTimeout() == null ? java.time.Duration.ofSeconds(3) : properties.connectTimeout());
  factory.setReadTimeout(properties.readTimeout() == null ? java.time.Duration.ofSeconds(5) : properties.readTimeout());
  restClient = RestClient.builder().requestFactory(factory).build();
 }
 public OfficialSourceResponse fetch(String url){ ResponseEntity<String> response=restClient.get().uri(url).retrieve().toEntity(String.class); String contentType=response.getHeaders().getContentType()==null?null:response.getHeaders().getContentType().toString(); return new OfficialSourceResponse(response.getBody(),contentType,Instant.now()); }
}
