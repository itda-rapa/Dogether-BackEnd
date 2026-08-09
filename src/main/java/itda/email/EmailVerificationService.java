package itda.email;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.email.dto.EmailVerificationChallengeResponse;
import itda.email.dto.EmailVerificationConfirmedResponse;
import itda.email.dto.EmailVerificationConfirmRequest;
import itda.email.dto.EmailVerificationSendRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
@Slf4j
public class EmailVerificationService {
    private static final int TOKEN_COLLISION_ATTEMPTS = 3;

    private final EmailVerificationHasher hasher;
    private final EmailVerificationCodeGenerator codeGenerator;
    private final EmailVerificationTokenGenerator tokenGenerator;
    private final EmailVerificationRedisStore redisStore;
    private final EmailDeliveryPublisher deliveryPublisher;
    private final EmailVerificationProperties properties;
    private final Clock clock;

    @Autowired
    public EmailVerificationService(EmailVerificationHasher hasher,
                                    EmailVerificationCodeGenerator codeGenerator,
                                    EmailVerificationTokenGenerator tokenGenerator,
                                    EmailVerificationRedisStore redisStore,
                                    EmailDeliveryPublisher deliveryPublisher,
                                    EmailVerificationProperties properties) {
        this(hasher, codeGenerator, tokenGenerator, redisStore, deliveryPublisher, properties, Clock.systemUTC());
    }

    EmailVerificationService(EmailVerificationHasher hasher,
                             EmailVerificationCodeGenerator codeGenerator,
                             EmailVerificationTokenGenerator tokenGenerator,
                             EmailVerificationRedisStore redisStore,
                             EmailDeliveryPublisher deliveryPublisher,
                             EmailVerificationProperties properties,
                             Clock clock) {
        this.hasher = hasher;
        this.codeGenerator = codeGenerator;
        this.tokenGenerator = tokenGenerator;
        this.redisStore = redisStore;
        this.deliveryPublisher = deliveryPublisher;
        this.properties = properties;
        this.clock = clock;
    }

    public EmailVerificationChallengeResponse request(EmailVerificationSendRequest request) {
        String email = normalizeEmail(request.email());
        String challengeId = UUID.randomUUID().toString();
        String code = codeGenerator.generate();
        EmailVerificationRedisStore.Challenge challenge = new EmailVerificationRedisStore.Challenge(
                challengeId, hasher.email(email), request.purpose(), hasher.code(challengeId, code)
        );
        EmailVerificationRedisStore.IssueResult issueResult;
        try {
            issueResult = redisStore.issue(challenge);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.error("Email verification issue failed. challengeId={}", challengeId, exception);
            throw deliveryUnavailable(exception);
        }
        if (issueResult == EmailVerificationRedisStore.IssueResult.COOLDOWN) {
            throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_RATE_LIMITED);
        }
        try {
            deliveryPublisher.publish(email, request.purpose(), code, challengeId);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.error("Email delivery publish failed. challengeId={}", challengeId, exception);
            try {
                redisStore.compensate(challenge);
            } catch (RuntimeException compensationException) {
                log.error("Email delivery compensation failed. challengeId={}", challengeId,
                        compensationException);
            }
            throw deliveryUnavailable(exception);
        }
        Instant expiresAt = clock.instant().plus(properties.challengeTtl());
        return new EmailVerificationChallengeResponse(challengeId, expiresAt, properties.cooldown().toSeconds());
    }

    public EmailVerificationConfirmedResponse confirm(EmailVerificationConfirmRequest request) {
        String submittedCodeHmac = hasher.code(request.challengeId(), request.code());
        for (int attempt = 0; attempt < TOKEN_COLLISION_ATTEMPTS; attempt++) {
            String token = tokenGenerator.generate();
            EmailVerificationRedisStore.ConfirmResult result = redisStore.confirm(
                    request.challengeId(), submittedCodeHmac, hasher.token(token)
            );
            switch (result) {
                case SUCCESS -> {
                    return new EmailVerificationConfirmedResponse(token,
                            clock.instant().plus(redisStore.tokenTtl()));
                }
                case CODE_MISMATCH -> throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH);
                case CHALLENGE_UNAVAILABLE -> throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_UNAVAILABLE);
                case ATTEMPTS_EXCEEDED -> throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED);
                case TOKEN_COLLISION -> { /* retry without consuming the challenge */ }
                case CORRUPTED -> throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_UNAVAILABLE);
            }
        }
        throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_UNAVAILABLE);
    }

    public void consume(String rawToken, String rawEmail, EmailVerificationPurpose purpose) {
        String email = normalizeEmail(rawEmail);
        if (!redisStore.consume(hasher.token(rawToken), hasher.email(email), purpose)) {
            throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);
        }
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private BusinessException deliveryUnavailable(RuntimeException exception) {
        return new BusinessException(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE);
    }
}
