package itda.petverification;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class PetVerificationHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String EXAMPLE_SECRET =
            "replace-with-a-random-pet-verification-hmac-secret-at-least-32-bytes";
    private static final String TEST_FALLBACK_SECRET = "test-only-pet-verification-hmac-secret";
    private static final int MINIMUM_SECRET_BYTES = 32;
    private final SecretKeySpec key;

    @Autowired
    public PetVerificationHasher(PetVerificationProperties properties, Environment environment) {
        String secret = properties.hmacSecret();
        if (secret == null || secret.isBlank() || secret.contains("${") || EXAMPLE_SECRET.equals(secret)) {
            if (isTestProfile(environment)) {
                secret = TEST_FALLBACK_SECRET;
            } else {
                throw new IllegalArgumentException("PET_VERIFICATION_HMAC_SECRET는 필수입니다.");
            }
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
            throw new IllegalArgumentException("PET_VERIFICATION_HMAC_SECRET는 UTF-8 기준 32바이트 이상이어야 합니다.");
        }
        key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public PetVerificationHasher(PetVerificationProperties properties) {
        this(properties, new StandardEnvironment());
    }

    private boolean isTestProfile(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if ("test".equals(profile)) return true;
        }
        return false;
    }

    public String registrationNumber(String canonicalDogRegNo) {
        return hmac("registration:" + canonicalDogRegNo);
    }

    public String token(String rawToken) {
        return hmac("token:" + rawToken);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("Pet 인증 HMAC 생성에 실패했습니다.", exception);
        }
    }
}
