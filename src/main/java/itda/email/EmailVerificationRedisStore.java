package itda.email;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class EmailVerificationRedisStore {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> issueScript;
    private final DefaultRedisScript<List> confirmScript;
    private final DefaultRedisScript<Long> consumeScript;
    private final EmailVerificationProperties properties;

    public EmailVerificationRedisStore(
            @Qualifier("emailStringRedisTemplate") StringRedisTemplate redisTemplate,
            @Qualifier("emailVerificationIssueScript") DefaultRedisScript<List> issueScript,
            @Qualifier("emailVerificationConfirmScript") DefaultRedisScript<List> confirmScript,
            @Qualifier("emailVerificationConsumeScript") DefaultRedisScript<Long> consumeScript,
            EmailVerificationProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.issueScript = issueScript;
        this.confirmScript = confirmScript;
        this.consumeScript = consumeScript;
        this.properties = properties;
    }

    public IssueResult issue(Challenge challenge) {
        List<?> result = redisTemplate.execute(
                issueScript,
                List.of(
                        EmailRedisKeys.challenge(challenge.challengeId()),
                        EmailRedisKeys.current(challenge.purpose(), challenge.emailHmac()),
                        EmailRedisKeys.cooldown(challenge.purpose(), challenge.emailHmac())
                ),
                "ISSUE", challenge.challengeId(), challenge.emailHmac(), challenge.purpose().name(),
                challenge.codeHmac(), Long.toString(properties.challengeTtl().toSeconds()),
                Long.toString(properties.cooldown().toSeconds()),
                "dogether:email:v1:challenge:", Integer.toString(properties.maxAttempts())
        );
        return number(result, 0) == 0 ? IssueResult.COOLDOWN : IssueResult.ISSUED;
    }

    public void compensate(Challenge challenge) {
        redisTemplate.execute(
                issueScript,
                List.of(
                        EmailRedisKeys.challenge(challenge.challengeId()),
                        EmailRedisKeys.current(challenge.purpose(), challenge.emailHmac()),
                        EmailRedisKeys.cooldown(challenge.purpose(), challenge.emailHmac())
                ),
                "COMPENSATE", challenge.challengeId(), "", "", "", "0", "0", "", "0"
        );
    }

    public ConfirmResult confirm(String challengeId, String submittedCodeHmac, String tokenHmac) {
        List<?> result = redisTemplate.execute(
                confirmScript,
                List.of(EmailRedisKeys.challenge(challengeId), EmailRedisKeys.token(tokenHmac)),
                submittedCodeHmac, challengeId, Long.toString(properties.tokenTtl().toSeconds()),
                "", "", EmailRedisKeys.currentPrefix()
        );
        return ConfirmResult.from(number(result, 0), (int) number(result, 1));
    }

    public boolean consume(String tokenHmac, String emailHmac, EmailVerificationPurpose purpose) {
        Long result = redisTemplate.execute(
                consumeScript,
                List.of(EmailRedisKeys.token(tokenHmac)), emailHmac, purpose.name()
        );
        return Long.valueOf(1L).equals(result);
    }

    public Duration tokenTtl() {
        return properties.tokenTtl();
    }

    private long number(List<?> result, int index) {
        if (result == null || result.size() <= index || !(result.get(index) instanceof Number number)) {
            throw new IllegalStateException("이메일 인증 Lua 결과가 올바르지 않습니다.");
        }
        return number.longValue();
    }

    public record Challenge(String challengeId, String emailHmac, EmailVerificationPurpose purpose,
                            String codeHmac) {
    }

    public enum IssueResult { ISSUED, COOLDOWN }

    public enum ConfirmResult {
        SUCCESS, CODE_MISMATCH, CHALLENGE_UNAVAILABLE, ATTEMPTS_EXCEEDED, TOKEN_COLLISION, CORRUPTED;

        static ConfirmResult from(long status, int ignored) {
            return switch ((int) status) {
                case 1 -> SUCCESS;
                case 0 -> CODE_MISMATCH;
                case -1 -> CHALLENGE_UNAVAILABLE;
                case -2 -> ATTEMPTS_EXCEEDED;
                case -3 -> TOKEN_COLLISION;
                default -> CORRUPTED;
            };
        }
    }
}
