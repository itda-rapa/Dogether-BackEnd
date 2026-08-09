package itda.email.infrastructure;

import itda.email.EmailVerificationPurpose;

public interface VerificationEmailSender {
    void send(String recipient, EmailVerificationPurpose purpose, String verificationCode);
}
