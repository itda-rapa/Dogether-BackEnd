package itda.common.eventhandler;

import static org.assertj.core.api.Assertions.assertThat;

import itda.common.constants.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler();

    @Test
    void lockFailureUsesDomainNeutralConflictCode() {
        var response = handler.handleConcurrentUpdateConflict(
                new PessimisticLockingFailureException("locked")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code())
                .isEqualTo(ErrorCode.CONCURRENT_UPDATE_CONFLICT.name());
    }
}
