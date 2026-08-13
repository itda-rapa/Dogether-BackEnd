package itda.email.infrastructure;

import itda.email.EmailVerificationProperties;
import itda.email.EmailVerificationPurpose;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

@Component
public class EmailVerificationMailTemplateRenderer {
    private static final String VERIFICATION_CODE = "{{VERIFICATION_CODE}}";
    private static final String PURPOSE_MESSAGE = "{{PURPOSE_MESSAGE}}";
    private static final String EXPIRY_TEXT = "{{EXPIRY_TEXT}}";
    private static final List<String> REQUIRED_PLACEHOLDERS = List.of(
            VERIFICATION_CODE, PURPOSE_MESSAGE, EXPIRY_TEXT
    );

    private final EmailVerificationProperties properties;
    private final String template;

    public EmailVerificationMailTemplateRenderer(EmailVerificationProperties properties) {
        this.properties = properties;
        this.template = loadTemplate();
        verifyTemplateContract(template);
    }

    public String render(EmailVerificationPurpose purpose, String verificationCode) {
        String rendered = template
                .replace(VERIFICATION_CODE, verificationCode)
                .replace(PURPOSE_MESSAGE, purposeMessage(purpose))
                .replace(EXPIRY_TEXT, expiryText(properties.challengeTtl()));
        verifyRenderedTemplate(rendered);
        return rendered;
    }

    private String loadTemplate() {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource("mail/verification-code.html").getInputStream(),
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            throw new IllegalStateException("이메일 인증 HTML 템플릿을 읽을 수 없습니다.", exception);
        }
    }

    private void verifyTemplateContract(String loadedTemplate) {
        if (REQUIRED_PLACEHOLDERS.stream().anyMatch(placeholder -> !loadedTemplate.contains(placeholder))) {
            throw new IllegalStateException("이메일 인증 HTML 템플릿 placeholder 계약이 올바르지 않습니다.");
        }
    }

    private void verifyRenderedTemplate(String renderedTemplate) {
        if (REQUIRED_PLACEHOLDERS.stream().anyMatch(renderedTemplate::contains)) {
            throw new IllegalStateException("이메일 인증 HTML 템플릿 렌더링이 완료되지 않았습니다.");
        }
    }

    private String purposeMessage(EmailVerificationPurpose purpose) {
        return switch (purpose) {
            case SIGNUP -> "아래 인증번호를 회원가입 화면에 입력해주세요.";
            case PASSWORD_RESET -> "아래 인증번호를 비밀번호 재설정 화면에 입력해주세요.";
        };
    }

    private String expiryText(Duration challengeTtl) {
        long totalSeconds = challengeTtl.toSeconds();
        return totalSeconds % 60 == 0 ? totalSeconds / 60 + "분" : totalSeconds + "초";
    }
}
