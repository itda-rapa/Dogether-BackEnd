package itda.email;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class EmailVerificationHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private final SecretKeySpec key;

    public EmailVerificationHasher(EmailVerificationProperties properties) {
        if (properties.hmacSecret() == null || properties.hmacSecret().isBlank()) {
            throw new IllegalArgumentException("EMAIL_VERIFICATION_HMAC_SECRET는 필수입니다.");
        }
        key = new SecretKeySpec(properties.hmacSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String email(String normalizedEmail) {
        return hmac("email:" + normalizedEmail);
    }

    public String code(String challengeId, String rawCode) {
        return hmac("code:" + challengeId + ":" + rawCode);
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
            throw new IllegalStateException("이메일 인증 HMAC 생성에 실패했습니다.", exception);
        }
    }
}
