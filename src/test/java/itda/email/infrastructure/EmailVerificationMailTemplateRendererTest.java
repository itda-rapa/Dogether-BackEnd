package itda.email.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import itda.email.EmailVerificationProperties;
import itda.email.EmailVerificationPurpose;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class EmailVerificationMailTemplateRendererTest {

    @Test
    void rendersSignupMessageCodeAndMinuteTtl() {
        String rendered = renderer(Duration.ofMinutes(5)).render(EmailVerificationPurpose.SIGNUP, "381527");

        assertThat(rendered)
                .contains("381527", "아래 인증번호를 회원가입 화면에 입력해주세요.", "5분간",
                        "cid:dogetherLogo")
                .doesNotContain("{{VERIFICATION_CODE}}", "{{PURPOSE_MESSAGE}}", "{{EXPIRY_TEXT}}")
                .doesNotContain("data:image/");
    }

    @Test
    void rendersPasswordResetMessageAndSecondTtl() {
        String rendered = renderer(Duration.ofSeconds(90))
                .render(EmailVerificationPurpose.PASSWORD_RESET, "381527");

        assertThat(rendered)
                .contains("381527", "아래 인증번호를 비밀번호 재설정 화면에 입력해주세요.", "90초간",
                        "cid:dogetherLogo")
                .doesNotContain("{{VERIFICATION_CODE}}", "{{PURPOSE_MESSAGE}}", "{{EXPIRY_TEXT}}")
                .doesNotContain("data:image/");
    }

    private EmailVerificationMailTemplateRenderer renderer(Duration challengeTtl) {
        return new EmailVerificationMailTemplateRenderer(new EmailVerificationProperties(
                "test-email-verification-hmac-secret-at-least-32-bytes", challengeTtl,
                Duration.ofMinutes(15), Duration.ofSeconds(60), Duration.ofMinutes(5), false,
                "fake", "stream", "group", "consumer", 5
        ));
    }
}
