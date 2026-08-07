package itda.email;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class EmailVerificationCodeGenerator {
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        return "%06d".formatted(random.nextInt(1_000_000));
    }
}
