package itda.petverification.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.PetSex;
import itda.petverification.PetVerificationHasher;
import itda.petverification.PetVerificationProperties;
import itda.petverification.domain.PetVerificationDeviceType;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

class AnimalInfoV3AdapterRestClientContractTest {

    private static final String SERVICE_KEY = "synthetic+service/key=value";

    private HttpServer server;
    private AnimalInfoV3Adapter adapter;
    private final List<String> rawQueries = new ArrayList<>();
    private int responseCode = 200;
    private String responseBody = successResponse();
    private Duration responseDelay = Duration.ZERO;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/1543061/animalInfoSrvc_v3/animalInfo_v3", this::respond);
        server.start();
        PetVerificationProperties properties = new PetVerificationProperties(
                "synthetic-pet-verification-hmac-secret", SERVICE_KEY,
                Duration.ofMinutes(15), Duration.ofSeconds(1), Duration.ofSeconds(1));
        adapter = new AnimalInfoV3Adapter(
                properties,
                new PetVerificationHasher(properties),
                RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build()
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsAllFourValidQueryCombinationsThroughRealRestClientAndMapsWrappedJson() {
        assertEvidence(adapter.verify(request(AnimalInfoV3Request.IdentifierType.REGISTRATION_NUMBER,
                "REG SYN+001/%", "Synthetic Owner+Name", null)));
        assertEvidence(adapter.verify(request(AnimalInfoV3Request.IdentifierType.REGISTRATION_NUMBER,
                "REG-SYN-002", null, LocalDate.of(1971, 5, 5))));
        assertEvidence(adapter.verify(request(AnimalInfoV3Request.IdentifierType.RFID,
                "RFID SYN+001/%", "Synthetic Owner+Name", null)));
        assertEvidence(adapter.verify(request(AnimalInfoV3Request.IdentifierType.RFID,
                "RFID-SYN-002", null, LocalDate.of(1971, 5, 5))));

        assertThat(rawQueries).hasSize(4).allSatisfy(raw -> {
            assertThat(raw).contains("serviceKey=synthetic%2Bservice%2Fkey%3Dvalue", "_type=json");
            assertThat(raw).doesNotContain("%252B", "%252F", "%2520", "serviceKey=synthetic+service");
        });
        assertThat(rawQueries.get(0)).contains(
                "dog_reg_no=REG%20SYN%2B001%2F%25", "owner_nm=Synthetic%20Owner%2BName");
        assertQuery(rawQueries.get(0), "dog_reg_no", "REG SYN+001/%", "owner_nm", "Synthetic Owner+Name");
        assertQuery(rawQueries.get(1), "dog_reg_no", "REG-SYN-002", "owner_birth", "710505");
        assertThat(rawQueries.get(2)).contains(
                "rfid_cd=RFID%20SYN%2B001%2F%25", "owner_nm=Synthetic%20Owner%2BName");
        assertQuery(rawQueries.get(2), "rfid_cd", "RFID SYN+001/%", "owner_nm", "Synthetic Owner+Name");
        assertQuery(rawQueries.get(3), "rfid_cd", "RFID-SYN-002", "owner_birth", "710505");
    }

    @Test
    void mapsARealRestClientReadTimeoutToUnavailable() {
        responseDelay = Duration.ofMillis(300);
        PetVerificationProperties timeoutProperties = new PetVerificationProperties(
                "synthetic-pet-verification-hmac-secret", SERVICE_KEY,
                Duration.ofMinutes(15), Duration.ofMillis(100), Duration.ofMillis(100));
        AnimalInfoV3Adapter timeoutAdapter = new AnimalInfoV3Adapter(timeoutProperties,
                new PetVerificationHasher(timeoutProperties),
                "http://127.0.0.1:" + server.getAddress().getPort());

        assertThatThrownBy(() -> timeoutAdapter.verify(request(
                AnimalInfoV3Request.IdentifierType.REGISTRATION_NUMBER,
                "REG-SYN-TIMEOUT", "Synthetic Owner", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.PET_VERIFICATION_UNAVAILABLE);
    }

    @Test
    void mapsProviderCodeWithoutGroundedNoMatchEvidenceToUnavailable() {
        responseBody = """
                {"response":{"header":{"resultCode":"03","resultMsg":"NO DATA"},"body":{}}}
                """;

        assertThatThrownBy(() -> adapter.verify(request(
                AnimalInfoV3Request.IdentifierType.REGISTRATION_NUMBER,
                "REG-SYN-003", "Synthetic Owner", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.PET_VERIFICATION_UNAVAILABLE);
    }

    @Test
    void mapsMalformedProviderJsonToUnavailableWithoutExposingWireDetails() {
        responseBody = "{not-json";

        assertUnavailable();
    }

    @Test
    void mapsNonSuccessHttpResponseToUnavailableWithoutTreatingItAsNoMatch() {
        responseCode = 503;
        responseBody = "synthetic gateway failure";

        assertUnavailable();
    }

    private void respond(HttpExchange exchange) throws IOException {
        rawQueries.add(exchange.getRequestURI().getRawQuery());
        if (!responseDelay.isZero()) {
            try {
                Thread.sleep(responseDelay);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("synthetic response interrupted", exception);
            }
        }
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseCode, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private AnimalInfoV3Request request(
            AnimalInfoV3Request.IdentifierType identifierType,
            String identifier,
            String ownerName,
            LocalDate ownerBirthDate
    ) {
        return new AnimalInfoV3Request(identifierType, identifier, ownerName, ownerBirthDate);
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> adapter.verify(request(
                AnimalInfoV3Request.IdentifierType.REGISTRATION_NUMBER,
                "REG-SYN-UNAVAILABLE", "Synthetic Owner", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.PET_VERIFICATION_UNAVAILABLE);
    }

    private void assertEvidence(itda.petverification.domain.PetVerification.Evidence evidence) {
        assertThat(evidence.deviceType()).isEqualTo(PetVerificationDeviceType.IMPLANTED);
        assertThat(evidence.registeredName()).isEqualTo("Synthetic Pet");
        assertThat(evidence.birthDate()).isEqualTo(LocalDate.of(2022, 1, 1));
        assertThat(evidence.sex()).isEqualTo(PetSex.FEMALE);
        assertThat(evidence.neutered()).isTrue();
    }

    private void assertQuery(String rawQuery, String identifierName, String identifierValue,
                             String ownerName, String ownerValue) {
        var query = UriComponentsBuilder.fromUriString("http://synthetic.test/?" + rawQuery)
                .build().getQueryParams();
        assertThat(query).containsKeys(identifierName, ownerName);
        assertThat(URLDecoder.decode(query.getFirst(identifierName), StandardCharsets.UTF_8))
                .isEqualTo(identifierValue);
        assertThat(URLDecoder.decode(query.getFirst(ownerName), StandardCharsets.UTF_8))
                .isEqualTo(ownerValue);
        assertThat(query).doesNotContainKeys(
                identifierName.equals("dog_reg_no") ? "rfid_cd" : "dog_reg_no",
                ownerName.equals("owner_nm") ? "owner_birth" : "owner_nm"
        );
    }

    private static String successResponse() {
        return """
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"item":{
                "dogRegNo":"  REG- SYN-001  ","rfidGubun":"Y","dogNm":"Synthetic Pet",
                "birthDt":"2022-01-01","sexNm":"암컷","kindNm":"Synthetic Breed","neuterYn":"중성",
                "aprGbNm":"승인"}}}}
                """;
    }
}
