package itda.petverification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@ExtendWith(MockitoExtension.class)
class PetVerificationRedisStoreContractTest {

    @Mock private StringRedisTemplate redis;
    @Mock private DefaultRedisScript<Long> issueScript;
    @Mock private DefaultRedisScript<List> reserveScript;
    @Mock private DefaultRedisScript<Long> releaseScript;
    @Mock private DefaultRedisScript<Long> finalizeScript;

    private PetVerificationRedisStore store;

    @BeforeEach
    void setUp() {
        PetVerificationProperties properties = new PetVerificationProperties(
                "synthetic-pet-verification-hmac-secret", "synthetic-service-key",
                Duration.ofMinutes(15), Duration.ofSeconds(1), Duration.ofSeconds(1));
        store = new PetVerificationRedisStore(
                redis, issueScript, reserveScript, releaseScript, finalizeScript,
                properties, new PetVerificationHasher(properties)
        );
    }

    @Test
    void treatsReleaseScriptNoOpAsAContainedNoOpRatherThanInfrastructureFailure() {
        given(redis.execute(eq(releaseScript), anyList(), eq("synthetic-reservation")))
                .willReturn(0L);

        assertThatCode(() -> store.release("synthetic-token", "synthetic-reservation"))
                .doesNotThrowAnyException();
    }

    @Test
    void treatsMissingReleaseScriptResultAsUnavailableInsteadOfSuccessfulRecovery() {
        given(redis.execute(eq(releaseScript), anyList(), eq("synthetic-reservation")))
                .willReturn(null);

        assertUnavailable(() -> store.release("synthetic-token", "synthetic-reservation"));
    }

    @Test
    void mapsRedisInfrastructureFailureDuringReleaseToUnavailable() {
        given(redis.execute(eq(releaseScript), anyList(), eq("synthetic-reservation")))
                .willThrow(new DataAccessResourceFailureException("synthetic Redis unavailable"));

        assertUnavailable(() -> store.release("synthetic-token", "synthetic-reservation"));
    }

    @Test
    void mapsReserveInfrastructureFailureToUnavailableRatherThanTokenInvalid() {
        given(redis.execute(eq(reserveScript), anyList(), eq("7"), eq("PET_CREATE"), eq(""), anyString()))
                .willThrow(new DataAccessResourceFailureException("synthetic Redis unavailable"));

        assertUnavailable(() -> store.reserve("synthetic-token", 7L, PetVerificationFlowType.PET_CREATE, null));
    }

    @Test
    void treatsMissingReserveScriptResultAsUnavailable() {
        given(redis.execute(eq(reserveScript), anyList(), eq("7"), eq("PET_CREATE"), eq(""), anyString()))
                .willReturn(null);

        assertUnavailable(() -> store.reserve("synthetic-token", 7L, PetVerificationFlowType.PET_CREATE, null));
    }

    @Test
    void treatsUnexpectedReserveScriptResultAsUnavailable() {
        given(redis.execute(eq(reserveScript), anyList(), eq("7"), eq("PET_CREATE"), eq(""), anyString()))
                .willReturn(List.of(2L));

        assertUnavailable(() -> store.reserve("synthetic-token", 7L, PetVerificationFlowType.PET_CREATE, null));
    }

    @Test
    void corruptEvidenceRemainsUnavailableWhenBestEffortReleaseAlsoFails() {
        given(redis.execute(eq(reserveScript), anyList(), eq("7"), eq("PET_CREATE"), eq(""), anyString()))
                .willAnswer(invocation -> List.of(1L, "reservationId", invocation.getArgument(5),
                        "provider", "ANIMAL_INFO_V3"));
        given(redis.execute(eq(releaseScript), anyList(), anyString()))
                .willThrow(new DataAccessResourceFailureException("synthetic Redis unavailable"));

        assertUnavailable(() -> store.reserve("synthetic-token", 7L, PetVerificationFlowType.PET_CREATE, null));
    }

    @Test
    void parsesTheAtomicLuaSnapshotWithoutReadingTheHashAfterReserve() {
        given(redis.execute(eq(reserveScript), anyList(), eq("7"), eq("PET_CREATE"), eq(""), anyString()))
                .willAnswer(invocation -> List.of(1L, "reservationId", invocation.getArgument(5),
                        "provider", "ANIMAL_INFO_V3",
                        "registrationNumberHmac", "a".repeat(64),
                        "sex", "FEMALE", "neutered", "true"));

        var reservation = store.reserve("synthetic-token", 7L, PetVerificationFlowType.PET_CREATE, null);

        assertThat(reservation.evidence().registrationNumberHmac()).isEqualTo("a".repeat(64));
        assertThat(reservation.evidence().sex().name()).isEqualTo("FEMALE");
        assertThat(reservation.evidence().neutered()).isTrue();
        verify(redis, never()).opsForHash();
    }

    @Test
    void finalizesOnlyWhenTheScriptExplicitlyDeletesTheReservedToken() {
        given(redis.execute(eq(finalizeScript), anyList(), eq("synthetic-reservation")))
                .willReturn(1L);

        assertThat(store.finalize("synthetic-token", "synthetic-reservation")).isTrue();
    }

    @Test
    void treatsFinalizeNoOpAsFalseWithoutTurningCommittedWorkIntoAnInfrastructureFailure() {
        given(redis.execute(eq(finalizeScript), anyList(), eq("synthetic-reservation")))
                .willReturn(0L);

        assertThat(store.finalize("synthetic-token", "synthetic-reservation")).isFalse();
    }

    @Test
    void treatsMissingOrUnexpectedFinalizeResultAsUnavailable() {
        given(redis.execute(eq(finalizeScript), anyList(), eq("missing-result")))
                .willReturn(null);
        given(redis.execute(eq(finalizeScript), anyList(), eq("unexpected-result")))
                .willReturn(2L);

        assertUnavailable(() -> store.finalize("synthetic-token", "missing-result"));
        assertUnavailable(() -> store.finalize("synthetic-token", "unexpected-result"));
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.PET_VERIFICATION_UNAVAILABLE);
    }
}
