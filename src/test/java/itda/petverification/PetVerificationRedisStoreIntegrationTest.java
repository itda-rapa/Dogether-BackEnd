package itda.petverification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.PetSex;
import itda.petverification.domain.PetVerificationDeviceType;
import itda.petverification.domain.PetVerificationProvider;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("redis")
@Testcontainers
class PetVerificationRedisStoreIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory factory;

    @AfterEach
    void tearDown() {
        if (factory != null) factory.destroy();
    }

    @Test
    void issuesHashedKeyWithAvailableStateAndOmitsNullableSnapshotFields() {
        StringRedisTemplate template = template();
        PetVerificationRedisStore store = store(template);
        var issued = store.issue(7L, PetVerificationFlowType.PET_CREATE, null, nullableEvidence());
        String key = PetVerificationRedisKeys.token(hasher().token(issued.rawToken()));

        assertThat(template.hasKey(PetVerificationRedisKeys.token(issued.rawToken()))).isFalse();
        assertThat(template.opsForHash().entries(key))
                .containsEntry("requesterUserId", "7")
                .containsEntry("flowType", "PET_CREATE")
                .containsEntry("status", "AVAILABLE")
                .doesNotContainKeys("targetPetId", "deviceType", "registeredName", "birthDate", "sex", "breedName", "neutered");
        assertThat(template.getExpire(key)).isPositive();
    }

    @Test
    void concurrentReserveHasExactlyOneWinnerAndMismatchedBindingsAreInvalid() throws Exception {
        StringRedisTemplate template = template();
        PetVerificationRedisStore store = store(template);
        var existing = store.issue(7L, PetVerificationFlowType.EXISTING_PET_VERIFY, 31L, evidence());

        assertInvalid(() -> store.reserve(existing.rawToken(), 8L, PetVerificationFlowType.EXISTING_PET_VERIFY, 31L));
        assertInvalid(() -> store.reserve(existing.rawToken(), 7L, PetVerificationFlowType.PET_CREATE, null));
        assertInvalid(() -> store.reserve(existing.rawToken(), 7L, PetVerificationFlowType.EXISTING_PET_VERIFY, 32L));

        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> reserve = () -> {
                try {
                    store.reserve(existing.rawToken(), 7L, PetVerificationFlowType.EXISTING_PET_VERIFY, 31L);
                    return true;
                } catch (BusinessException exception) {
                    return false;
                }
            };
            long winners = executor.invokeAll(List.of(reserve, reserve)).stream()
                    .filter(result -> {
                        try { return result.get(); } catch (Exception exception) { throw new AssertionError(exception); }
                    }).count();
            assertThat(winners).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void mapsMissingAndAlreadyReservedTokensToInvalid() {
        StringRedisTemplate template = template();
        PetVerificationRedisStore store = store(template);

        assertInvalid(() -> store.reserve("missing-token", 7L, PetVerificationFlowType.PET_CREATE, null));

        var issued = store.issue(7L, PetVerificationFlowType.PET_CREATE, null, evidence());
        store.reserve(issued.rawToken(), 7L, PetVerificationFlowType.PET_CREATE, null);

        assertInvalid(() -> store.reserve(issued.rawToken(), 7L, PetVerificationFlowType.PET_CREATE, null));
    }

    @Test
    void reservesAndDeserializesTheLuaEvidenceSnapshotInTheSameRedisEvaluation() {
        StringRedisTemplate template = template();
        PetVerificationRedisStore store = store(template);
        var issued = store.issue(7L, PetVerificationFlowType.PET_CREATE, null, evidence());

        var reservation = store.reserve(issued.rawToken(), 7L, PetVerificationFlowType.PET_CREATE, null);

        assertThat(reservation.reservationId()).isNotBlank();
        assertThat(reservation.evidence()).isEqualTo(evidence());
    }

    @Test
    void mapsCorruptReserveBindingMetadataToUnavailable() {
        StringRedisTemplate template = template();
        PetVerificationRedisStore store = store(template);

        assertUnavailableForEachMutation(template, store, PetVerificationFlowType.PET_CREATE, null, (key, hash) ->
                hash.delete(key, "status"));
        assertUnavailableForEachMutation(template, store, PetVerificationFlowType.PET_CREATE, null, (key, hash) ->
                hash.put(key, "status", "UNKNOWN"));

        assertUnavailableForEachMutation(template, store, PetVerificationFlowType.PET_CREATE, null, (key, hash) ->
                hash.delete(key, "requesterUserId"));
        assertUnavailableForEachMutation(template, store, PetVerificationFlowType.PET_CREATE, null, (key, hash) ->
                hash.put(key, "requesterUserId", ""));
        assertUnavailableForEachMutation(template, store, PetVerificationFlowType.PET_CREATE, null, (key, hash) ->
                hash.put(key, "requesterUserId", "   "));

        assertUnavailableForEachMutation(template, store, PetVerificationFlowType.PET_CREATE, null, (key, hash) ->
                hash.delete(key, "flowType"));
        assertUnavailableForEachMutation(template, store, PetVerificationFlowType.PET_CREATE, null, (key, hash) ->
                hash.put(key, "flowType", ""));
        assertUnavailableForEachMutation(template, store, PetVerificationFlowType.PET_CREATE, null, (key, hash) ->
                hash.put(key, "flowType", "   "));
        assertUnavailableForEachMutation(template, store, PetVerificationFlowType.PET_CREATE, null, (key, hash) ->
                hash.put(key, "flowType", "UNKNOWN"));

        assertUnavailableForEachMutation(template, store, PetVerificationFlowType.EXISTING_PET_VERIFY, 31L, (key, hash) ->
                hash.delete(key, "targetPetId"));
        assertUnavailableForEachMutation(template, store, PetVerificationFlowType.EXISTING_PET_VERIFY, 31L, (key, hash) ->
                hash.put(key, "targetPetId", ""));
        assertUnavailableForEachMutation(template, store, PetVerificationFlowType.EXISTING_PET_VERIFY, 31L, (key, hash) ->
                hash.put(key, "targetPetId", "   "));
        assertUnavailableForEachMutation(template, store, PetVerificationFlowType.PET_CREATE, null, (key, hash) ->
                hash.put(key, "targetPetId", "31"));
    }

    @Test
    void distinguishesRequiredHmacCorruptionFromNullableSnapshotOmissionAndMalformedSnapshotValues() {
        StringRedisTemplate template = template();
        PetVerificationRedisStore store = store(template);

        for (String hmac : Arrays.asList(null, "not-a-64-character-lowercase-hex-hmac")) {
            var issued = store.issue(7L, PetVerificationFlowType.PET_CREATE, null, evidence());
            String key = PetVerificationRedisKeys.token(hasher().token(issued.rawToken()));
            if (hmac == null) template.opsForHash().delete(key, "registrationNumberHmac");
            else template.opsForHash().put(key, "registrationNumberHmac", hmac);

            assertUnavailable(() -> store.reserve(issued.rawToken(), 7L, PetVerificationFlowType.PET_CREATE, null));
        }

        var nullable = store.issue(7L, PetVerificationFlowType.PET_CREATE, null, nullableEvidence());
        var nullableReservation = store.reserve(nullable.rawToken(), 7L, PetVerificationFlowType.PET_CREATE, null);
        assertThat(nullableReservation.evidence().deviceType()).isNull();
        assertThat(nullableReservation.evidence().birthDate()).isNull();
        assertThat(nullableReservation.evidence().sex()).isNull();
        assertThat(nullableReservation.evidence().neutered()).isNull();

        Map<String, String> malformedValues = Map.of(
                "deviceType", "UNSUPPORTED", "birthDate", "2022-13-40",
                "sex", "UNKNOWN", "neutered", "not-a-boolean"
        );
        for (Map.Entry<String, String> malformed : malformedValues.entrySet()) {
            var issued = store.issue(7L, PetVerificationFlowType.PET_CREATE, null, evidence());
            String key = PetVerificationRedisKeys.token(hasher().token(issued.rawToken()));
            template.opsForHash().put(key, malformed.getKey(), malformed.getValue());

            assertUnavailable(() -> store.reserve(issued.rawToken(), 7L, PetVerificationFlowType.PET_CREATE, null));
        }

        var valid = store.issue(7L, PetVerificationFlowType.EXISTING_PET_VERIFY, 31L, evidence());
        assertInvalid(() -> store.reserve(valid.rawToken(), 8L,
                PetVerificationFlowType.EXISTING_PET_VERIFY, 31L));
    }

    @Test
    void releaseAndFinalizeRequireTheMatchingReservationAndDoNotExtendTtl() {
        StringRedisTemplate template = template();
        PetVerificationRedisStore store = store(template);
        var issued = store.issue(7L, PetVerificationFlowType.PET_CREATE, null, evidence());
        String key = PetVerificationRedisKeys.token(hasher().token(issued.rawToken()));
        Long beforeReserve = template.getExpire(key);
        var reservation = store.reserve(issued.rawToken(), 7L, PetVerificationFlowType.PET_CREATE, null);
        Long afterReserve = template.getExpire(key);

        store.release(issued.rawToken(), "wrong-reservation");
        assertThat(template.opsForHash().get(key, "status")).isEqualTo("RESERVED");
        assertThat(afterReserve).isLessThanOrEqualTo(beforeReserve);
        store.release(issued.rawToken(), reservation.reservationId());
        assertThat(template.opsForHash().get(key, "status")).isEqualTo("AVAILABLE");
        Long afterRelease = template.getExpire(key);
        assertThat(afterRelease).isLessThanOrEqualTo(afterReserve);

        var secondReservation = store.reserve(issued.rawToken(), 7L, PetVerificationFlowType.PET_CREATE, null);
        store.finalize(issued.rawToken(), "wrong-reservation");
        assertThat(template.hasKey(key)).isTrue();
        store.finalize(issued.rawToken(), secondReservation.reservationId());
        assertThat(template.hasKey(key)).isFalse();
        assertInvalid(() -> store.reserve(issued.rawToken(), 7L, PetVerificationFlowType.PET_CREATE, null));
    }

    private StringRedisTemplate template() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        configuration.setDatabase(2);
        factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    private PetVerificationRedisStore store(StringRedisTemplate template) {
        return new PetVerificationRedisStore(template, script("redis/pet-verification-issue.lua"),
                listScript("redis/pet-verification-reserve.lua"), script("redis/pet-verification-release.lua"),
                script("redis/pet-verification-finalize.lua"), properties(), hasher());
    }

    private DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    private DefaultRedisScript<List> listScript(String path) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(List.class);
        return script;
    }

    private PetVerificationProperties properties() {
        return new PetVerificationProperties("synthetic-pet-verification-hmac-secret", "synthetic-service-key",
                Duration.ofSeconds(60), Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    private PetVerificationHasher hasher() { return new PetVerificationHasher(properties()); }

    private PetVerificationEvidence evidence() {
        return new PetVerificationEvidence(PetVerificationProvider.ANIMAL_INFO_V3, "a".repeat(64),
                PetVerificationDeviceType.IMPLANTED, "테스트견", LocalDate.of(2022, 1, 1),
                PetSex.FEMALE, "테스트품종", true);
    }

    private PetVerificationEvidence nullableEvidence() {
        return new PetVerificationEvidence(PetVerificationProvider.ANIMAL_INFO_V3, "b".repeat(64),
                null, null, null, null, null, null);
    }

    private void assertUnavailableForEachMutation(
            StringRedisTemplate template,
            PetVerificationRedisStore store,
            PetVerificationFlowType flowType,
            Long targetPetId,
            RedisHashMutation mutation
    ) {
        var issued = store.issue(7L, flowType, targetPetId, evidence());
        String key = PetVerificationRedisKeys.token(hasher().token(issued.rawToken()));
        mutation.apply(key, template.opsForHash());

        assertUnavailable(() -> store.reserve(issued.rawToken(), 7L, flowType, targetPetId));
    }

    @FunctionalInterface
    private interface RedisHashMutation {
        void apply(String key, org.springframework.data.redis.core.HashOperations<String, Object, Object> hash);
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.PET_VERIFICATION_TOKEN_INVALID);
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.PET_VERIFICATION_UNAVAILABLE);
    }
}
