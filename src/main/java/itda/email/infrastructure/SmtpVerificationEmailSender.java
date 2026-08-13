package itda.email.infrastructure;

import itda.email.EmailVerificationPurpose;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.email-verification", name = "sender-mode", havingValue = "smtp")
public class SmtpVerificationEmailSender implements VerificationEmailSender {
    private final JavaMailSender mailSender;
    private final EmailVerificationMailTemplateRenderer templateRenderer;
    private final String from;

    public SmtpVerificationEmailSender(JavaMailSender mailSender,
                                       EmailVerificationMailTemplateRenderer templateRenderer,
                                       @Value("${MAIL_FROM:}") String from) {
        this.mailSender = mailSender;
        this.templateRenderer = templateRenderer;
        this.from = from;
    }

    @Override
    public void send(String recipient, EmailVerificationPurpose purpose, String verificationCode) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(recipient);
            helper.setSubject(purpose == EmailVerificationPurpose.SIGNUP
                    ? "[Dogether] 회원가입 이메일 인증번호" : "[Dogether] 비밀번호 재설정 인증번호");
            helper.setText(templateRenderer.render(purpose, verificationCode), true);
            helper.addInline("dogetherLogo", new ClassPathResource("mail/dogether-logo.png"), "image/png");
            mailSender.send(message);
            log.info("Verification email sent. recipient={}, purpose={}", mask(recipient), purpose);
        } catch (MessagingException | RuntimeException exception) {
            throw new IllegalStateException("인증 이메일 전송에 실패했습니다.", exception);
        }
    }

    private String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
