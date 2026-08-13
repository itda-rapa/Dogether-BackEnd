package itda.email.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import itda.email.EmailVerificationProperties;
import itda.email.EmailVerificationPurpose;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class SmtpVerificationEmailSenderTest {
    @Mock private JavaMailSender mailSender;

    @Test
    void sendsSignupAsUtf8Html() throws Exception {
        MimeMessage message = send(EmailVerificationPurpose.SIGNUP, "381527");
        message.saveChanges();

        assertThat(message.getSubject()).isEqualTo("[Dogether] 회원가입 이메일 인증번호");
        assertHtmlAndInlineLogo(message, "381527", "아래 인증번호를 회원가입 화면에 입력해주세요.");
    }

    @Test
    void sendsPasswordResetAsUtf8Html() throws Exception {
        MimeMessage message = send(EmailVerificationPurpose.PASSWORD_RESET, "381527");
        message.saveChanges();

        assertThat(message.getSubject()).isEqualTo("[Dogether] 비밀번호 재설정 인증번호");
        assertHtmlAndInlineLogo(message, "381527", "아래 인증번호를 비밀번호 재설정 화면에 입력해주세요.");
    }

    private MimeMessage send(EmailVerificationPurpose purpose, String verificationCode) {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        given(mailSender.createMimeMessage()).willReturn(message);
        SmtpVerificationEmailSender sender = new SmtpVerificationEmailSender(
                mailSender, renderer(), "no-reply@dogether.test"
        );

        sender.send("user@example.com", purpose, verificationCode);

        then(mailSender).should().send(message);
        return message;
    }

    private EmailVerificationMailTemplateRenderer renderer() {
        return new EmailVerificationMailTemplateRenderer(new EmailVerificationProperties(
                "test-email-verification-hmac-secret-at-least-32-bytes", Duration.ofMinutes(5),
                Duration.ofMinutes(15), Duration.ofSeconds(60), Duration.ofMinutes(5), false,
                "fake", "stream", "group", "consumer", 5
        ));
    }

    private void assertHtmlAndInlineLogo(MimeMessage message, String verificationCode, String purposeMessage)
            throws Exception {
        assertThat(message.getContentType()).containsIgnoringCase("multipart/");

        MimeBodyPart htmlPart = findHtmlPart(message);
        assertThat(htmlPart).isNotNull();
        assertThat(htmlPart.getContentType()).containsIgnoringCase("text/html").containsIgnoringCase("charset=UTF-8");
        assertThat((String) htmlPart.getContent())
                .contains(verificationCode, purposeMessage, "cid:dogetherLogo");

        MimeBodyPart inlineLogoPart = findInlineLogoPart(message);
        assertThat(inlineLogoPart).isNotNull();
        assertThat(inlineLogoPart.getContentID()).isEqualTo("<dogetherLogo>");
        assertThat(inlineLogoPart.getContentType()).containsIgnoringCase("image/png");
    }

    private MimeBodyPart findHtmlPart(Part part) throws Exception {
        if (part.isMimeType("text/html")) {
            return (MimeBodyPart) part;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int index = 0; index < multipart.getCount(); index++) {
                MimeBodyPart htmlPart = findHtmlPart(multipart.getBodyPart(index));
                if (htmlPart != null) {
                    return htmlPart;
                }
            }
        }
        return null;
    }

    private MimeBodyPart findInlineLogoPart(Part part) throws Exception {
        if (part instanceof MimeBodyPart mimeBodyPart
                && "<dogetherLogo>".equals(mimeBodyPart.getContentID())) {
            return mimeBodyPart;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int index = 0; index < multipart.getCount(); index++) {
                MimeBodyPart inlineLogoPart = findInlineLogoPart(multipart.getBodyPart(index));
                if (inlineLogoPart != null) {
                    return inlineLogoPart;
                }
            }
        }
        return null;
    }
}
