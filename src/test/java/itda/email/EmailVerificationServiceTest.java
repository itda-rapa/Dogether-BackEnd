package itda.email;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.email.dto.EmailVerificationConfirmRequest;
import itda.email.dto.EmailVerificationSendRequest;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {
    private final EmailVerificationProperties properties = new EmailVerificationProperties(
            "test-email-verification-hmac-secret-at-least-32-bytes", Duration.ofMinutes(5),
            Duration.ofMinutes(15), Duration.ofSeconds(60), Duration.ofMinutes(5), false,
            "fake", "stream", "group", "consumer", 5
    );
    @Mock private EmailVerificationCodeGenerator codeGenerator;
    @Mock private EmailVerificationTokenGenerator tokenGenerator;
    @Mock private EmailVerificationRedisStore redisStore;
    @Mock private EmailDeliveryPublisher deliveryPublisher;
    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(new EmailVerificationHasher(properties), codeGenerator,
                tokenGenerator, redisStore, deliveryPublisher, properties);
    }

    @Test
    void requestRejectsCooldownWithoutPublishing() {
        given(codeGenerator.generate()).willReturn("123456");
        given(redisStore.issue(org.mockito.ArgumentMatchers.any()))
                .willReturn(EmailVerificationRedisStore.IssueResult.COOLDOWN);

        assertThatThrownBy(() -> service.request(new EmailVerificationSendRequest(
                "user@example.com", EmailVerificationPurpose.SIGNUP)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_VERIFICATION_RATE_LIMITED);
        then(deliveryPublisher).shouldHaveNoInteractions();
    }

    @Test
    void requestConvertsIssueInfrastructureFailureWithoutPublishing() {
        given(codeGenerator.generate()).willReturn("123456");
        given(redisStore.issue(org.mockito.ArgumentMatchers.any()))
                .willThrow(new IllegalStateException("Connection refused localhost:6379"));

        assertThatThrownBy(() -> service.request(new EmailVerificationSendRequest(
                "user@example.com", EmailVerificationPurpose.SIGNUP)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE);
        assertThatThrownBy(() -> service.request(new EmailVerificationSendRequest(
                "user@example.com", EmailVerificationPurpose.SIGNUP)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE.getDescription())
                .hasMessageNotContaining("Connection refused");
        then(deliveryPublisher).shouldHaveNoInteractions();
    }

    @Test
    void requestPreservesIssueBusinessException() {
        given(codeGenerator.generate()).willReturn("123456");
        given(redisStore.issue(org.mockito.ArgumentMatchers.any()))
                .willThrow(new BusinessException(ErrorCode.EMAIL_VERIFICATION_RATE_LIMITED));

        assertThatThrownBy(() -> service.request(new EmailVerificationSendRequest(
                "user@example.com", EmailVerificationPurpose.SIGNUP)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_VERIFICATION_RATE_LIMITED);
        then(deliveryPublisher).shouldHaveNoInteractions();
    }

    @Test
    void requestCompensatesAndConvertsPublishInfrastructureFailure() {
        issueSuccessfully();
        willThrow(new IllegalStateException("XADD unavailable")).given(deliveryPublisher)
                .publish(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> service.request(new EmailVerificationSendRequest(
                "user@example.com", EmailVerificationPurpose.SIGNUP)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE);
        then(redisStore).should().compensate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void requestKeepsDeliveryUnavailableWhenCompensationFails() {
        issueSuccessfully();
        willThrow(new IllegalStateException("XADD unavailable")).given(deliveryPublisher)
                .publish(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        willThrow(new IllegalStateException("Redis unavailable")).given(redisStore)
                .compensate(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> service.request(new EmailVerificationSendRequest(
                "user@example.com", EmailVerificationPurpose.SIGNUP)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_DELIVERY_UNAVAILABLE);
        then(redisStore).should().compensate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void confirmRetriesTokenCollisionWithoutTreatingChallengeAsConsumed() {
        given(tokenGenerator.generate()).willReturn("first-token", "second-token");
        given(redisStore.confirm(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .willReturn(EmailVerificationRedisStore.ConfirmResult.TOKEN_COLLISION,
                        EmailVerificationRedisStore.ConfirmResult.SUCCESS);
        given(redisStore.tokenTtl()).willReturn(Duration.ofMinutes(15));

        var response = service.confirm(new EmailVerificationConfirmRequest("challenge", "123456"));

        assertThat(response.verificationToken()).isEqualTo("second-token");
        then(redisStore).should(org.mockito.Mockito.times(2)).confirm(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void consumeRejectsBindingMismatch() {
        given(redisStore.consume(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(EmailVerificationPurpose.SIGNUP))).willReturn(false);

        assertThatThrownBy(() -> service.consume("token", "user@example.com", EmailVerificationPurpose.SIGNUP))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);
    }

    private void issueSuccessfully() {
        given(codeGenerator.generate()).willReturn("123456");
        given(redisStore.issue(org.mockito.ArgumentMatchers.any()))
                .willReturn(EmailVerificationRedisStore.IssueResult.ISSUED);
    }
}
