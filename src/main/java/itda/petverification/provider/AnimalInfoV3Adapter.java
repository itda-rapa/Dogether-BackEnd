package itda.petverification.provider;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.PetSex;
import itda.petverification.PetVerificationHasher;
import itda.petverification.PetVerificationProperties;
import itda.petverification.domain.PetVerificationDeviceType;
import itda.petverification.domain.PetVerificationProvider;
import itda.petverification.domain.PetVerification.Evidence;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AnimalInfoV3Adapter {
    private static final String BASE_URL = "https://apis.data.go.kr";
    private static final String PATH = "/1543061/animalInfoSrvc_v3/animalInfo_v3";

    private final RestClient restClient;
    private final PetVerificationProperties properties;
    private final PetVerificationHasher hasher;

    @Autowired
    public AnimalInfoV3Adapter(PetVerificationProperties properties, PetVerificationHasher hasher) {
        this(properties, hasher, BASE_URL);
    }

    /** Package-private seam for local HTTP timeout tests using the configured client settings. */
    AnimalInfoV3Adapter(
            PetVerificationProperties properties,
            PetVerificationHasher hasher,
            String baseUrl
    ) {
        this.properties = properties;
        this.hasher = hasher;
        this.restClient = restClient(properties, baseUrl);
    }

    static RestClient restClient(PetVerificationProperties properties, String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout() == null
                ? Duration.ofSeconds(3) : properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout() == null
                ? Duration.ofSeconds(5) : properties.readTimeout());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** Allows provider HTTP behavior to be exercised with a test-specific RestClient. */
    AnimalInfoV3Adapter(
            PetVerificationProperties properties,
            PetVerificationHasher hasher,
            RestClient restClient
    ) {
        this.properties = properties;
        this.hasher = hasher;
        this.restClient = restClient;
    }

    public Evidence verify(AnimalInfoV3Request request) {
        if (properties.serviceKey() == null || properties.serviceKey().isBlank()) {
            throw unavailable();
        }
        try {
            AnimalInfoV3RawResponse raw = restClient.get()
                    .uri(uri(request))
                    .retrieve()
                    .body(AnimalInfoV3RawResponse.class);
            return normalize(raw);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private URI uri(AnimalInfoV3Request request) {
        String identifierParameter = request.identifierType()
                == AnimalInfoV3Request.IdentifierType.REGISTRATION_NUMBER
                ? "dog_reg_no=" : "rfid_cd=";
        String ownerParameter = request.usesOwnerName()
                ? "owner_nm=" + encodeQueryValue(request.ownerName())
                : "owner_birth=" + encodeQueryValue(request.formattedOwnerBirthDate());
        return URI.create(PATH
                + "?serviceKey=" + encodeQueryValue(properties.serviceKey())
                + "&_type=json"
                + "&" + identifierParameter + encodeQueryValue(request.identifier())
                + "&" + ownerParameter);
    }

    private String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Evidence normalize(AnimalInfoV3RawResponse raw) {
        if (raw == null || raw.response() == null || raw.response().header() == null) {
            throw unavailable();
        }
        AnimalInfoV3RawResponse.Header header = raw.response().header();
        if (!"00".equals(header.resultCode())) {
            if (isIdentifierParameterError(header)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            throw unavailable();
        }
        AnimalInfoV3RawResponse.Item item = raw.response().body() == null
                ? null : raw.response().body().item();
        if (item == null || item.dogRegNo() == null) {
            throw unavailable();
        }
        String canonicalDogRegNo = item.dogRegNo().strip();
        if (canonicalDogRegNo.isBlank()) {
            throw unavailable();
        }
        return new Evidence(
                PetVerificationProvider.ANIMAL_INFO_V3,
                hasher.registrationNumber(canonicalDogRegNo),
                deviceType(item.rfidGubun()),
                optional(item.dogNm()),
                date(item.birthDt()),
                sex(item.sexNm()),
                optional(item.kindNm()),
                neutered(item.neuterYn())
        );
    }

    private boolean isIdentifierParameterError(AnimalInfoV3RawResponse.Header header) {
        if (!"10".equals(header.resultCode())) {
            return false;
        }
        return hasIdentifierFormatSignal(header.resultMsg())
                || hasIdentifierFormatSignal(header.errorMsg());
    }

    private boolean hasIdentifierFormatSignal(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return normalized.matches(".*\\binvalid\\s+(dog_reg_no|rfid_cd)\\s+format\\b.*")
                || normalized.matches(".*\\b(dog_reg_no|rfid_cd)\\s+format\\s+(?:is\\s+)?invalid\\b.*")
                || normalized.matches(".*\\bformat\\s+(?:is\\s+)?invalid\\s+for\\s+(dog_reg_no|rfid_cd)\\b.*");
    }

    private PetVerificationDeviceType deviceType(String value) {
        if (blank(value)) return null;
        return switch (value) {
            case "Y" -> PetVerificationDeviceType.IMPLANTED;
            case "M" -> PetVerificationDeviceType.EXTERNAL;
            case "N" -> PetVerificationDeviceType.TAG;
            default -> throw unavailable();
        };
    }

    private PetSex sex(String value) {
        if (blank(value)) return null;
        return switch (value) {
            case "수컷" -> PetSex.MALE;
            case "암컷" -> PetSex.FEMALE;
            default -> throw unavailable();
        };
    }

    private Boolean neutered(String value) {
        if (blank(value)) return null;
        return switch (value) {
            case "중성" -> true;
            case "미중성" -> false;
            default -> throw unavailable();
        };
    }

    private LocalDate date(String value) {
        if (blank(value)) return null;
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException exception) {
            throw unavailable();
        }
    }

    private String optional(String value) {
        return blank(value) ? null : value;
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.PET_VERIFICATION_UNAVAILABLE);
    }
}
