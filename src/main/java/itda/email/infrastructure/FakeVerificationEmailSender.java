package itda.email.infrastructure;

import itda.email.EmailVerificationPurpose;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.email-verification", name = "sender-mode", havingValue = "fake", matchIfMissing = true)
public class FakeVerificationEmailSender implements VerificationEmailSender {
    @Override
    public void send(String recipient, EmailVerificationPurpose purpose, String verificationCode) {
        log.info("Fake verification email accepted. recipient={}, purpose={}", mask(recipient), purpose);
    }

    private String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
