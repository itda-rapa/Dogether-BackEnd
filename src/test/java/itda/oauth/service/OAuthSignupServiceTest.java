package itda.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import itda.user.service.PublicTagGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class OAuthSignupServiceTest {

    @Mock private PublicTagGenerator publicTagGenerator;
    @Mock private OAuthSignupTransactionService transactionService;

    @Test
    void retriesOnlyPublicTagCollisionWithFreshTransactionAttempt() {
        given(publicTagGenerator.generate("사용자")).willReturn("사용자#AAAA", "사용자#BBBB");
        given(transactionService.completeAttempt(any(), any(), any()))
                .willThrow(new DataIntegrityViolationException("duplicate key uk_users_public_tag"))
                .willReturn("completed");
        OAuthSignupService service = new OAuthSignupService(
                publicTagGenerator, transactionService);

        String result = service.complete(new OAuthSignupCommand("signup-token", " 사용자 ", "1168010100"),
                user -> "completed");
        assertThat(result).isEqualTo("completed");

        then(transactionService).should(org.mockito.Mockito.times(2))
                .completeAttempt(any(), any(), any());
    }

    @Test
    void lateEmailCollisionBecomesSafeRestartConflict() {
        given(publicTagGenerator.generate("사용자")).willReturn("사용자#AAAA");
        given(transactionService.completeAttempt(any(), any(), any()))
                .willThrow(new DataIntegrityViolationException("duplicate key uk_users_email_lower"));
        OAuthSignupService service = new OAuthSignupService(
                publicTagGenerator, transactionService);

        assertThatThrownBy(() -> service.complete(new OAuthSignupCommand(
                "signup-token", "사용자", "1168010100"), user -> "never"))
                .isInstanceOf(OAuthFlowException.class)
                .extracting(error -> ((OAuthFlowException) error).getFailure())
                .isEqualTo(OAuthFlowFailure.CONCURRENT_UPDATE_CONFLICT);
        then(publicTagGenerator).should().generate("사용자");
        then(transactionService).should().completeAttempt(any(), any(), any());
    }
}
