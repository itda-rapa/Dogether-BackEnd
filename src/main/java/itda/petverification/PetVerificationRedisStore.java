package itda.petverification;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.petverification.domain.PetVerificationDeviceType;
import itda.petverification.domain.PetVerificationProvider;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class PetVerificationRedisStore {
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> issueScript;
    private final DefaultRedisScript<Long> reserveScript;
    private final DefaultRedisScript<Long> releaseScript;
    private final DefaultRedisScript<Long> finalizeScript;
    private final PetVerificationProperties properties;
    private final PetVerificationHasher hasher;

    public PetVerificationRedisStore(
            @Qualifier("petVerificationStringRedisTemplate") StringRedisTemplate redis,
            @Qualifier("petVerificationIssueScript") DefaultRedisScript<Long> issueScript,
            @Qualifier("petVerificationReserveScript") DefaultRedisScript<Long> reserveScript,
            @Qualifier("petVerificationReleaseScript") DefaultRedisScript<Long> releaseScript,
            @Qualifier("petVerificationFinalizeScript") DefaultRedisScript<Long> finalizeScript,
            PetVerificationProperties properties,
            PetVerificationHasher hasher
    ) {
        this.redis = redis;
        this.issueScript = issueScript;
        this.reserveScript = reserveScript;
        this.releaseScript = releaseScript;
        this.finalizeScript = finalizeScript;
        this.properties = properties;
        this.hasher = hasher;
    }

    public IssuedToken issue(Long userId, PetVerificationFlowType flowType, Long targetPetId,
                             PetVerificationEvidence evidence) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("requesterUserId", userId.toString());
        values.put("flowType", flowType.name());
        if (targetPetId != null) values.put("targetPetId", targetPetId.toString());
        values.put("provider", evidence.provider().name());
        values.put("registrationNumberHmac", evidence.registrationNumberHmac());
        put(values, "deviceType", evidence.deviceType());
        put(values, "registeredName", evidence.registeredName());
        put(values, "birthDate", evidence.birthDate());
        put(values, "sex", evidence.sex());
        put(values, "breedName", evidence.breedName());
        put(values, "neutered", evidence.neutered());
        values.put("status", "AVAILABLE");
        Duration ttl = properties.tokenTtl() == null ? Duration.ofMinutes(15) : properties.tokenTtl();
        for (int attempt = 0; attempt < 3; attempt++) {
            String rawToken = UUID.randomUUID().toString() + UUID.randomUUID();
            String key = PetVerificationRedisKeys.token(hasher.token(rawToken));
            Object[] arguments = arguments(values, ttl);
            Long result = execute(issueScript, List.of(key), arguments);
            if (Long.valueOf(1L).equals(result)) {
                return new IssuedToken(rawToken, Instant.now().plus(ttl));
            }
            if (!Long.valueOf(0L).equals(result)) {
                throw unavailable();
            }
        }
        throw unavailable();
    }

    public Reservation reserve(String rawToken, Long userId, PetVerificationFlowType flowType,
                               Long targetPetId) {
        String key = key(rawToken);
        String reservationId = UUID.randomUUID().toString();
        Long result = execute(reserveScript, List.of(key), userId.toString(), flowType.name(),
                targetPetId == null ? "" : targetPetId.toString(), reservationId);
        if (Long.valueOf(1L).equals(result)) {
            Map<Object, Object> map = entries(key);
            try {
                return new Reservation(reservationId, evidence(map));
            } catch (RuntimeException exception) {
                if (exception instanceof BusinessException businessException) {
                    throw businessException;
                }
                try {
                    release(rawToken, reservationId);
                } catch (RuntimeException releaseFailure) {
                    // The token may remain reserved, but corrupt state must remain a 503.
                }
                throw unavailable();
            }
        }
        if (Long.valueOf(0L).equals(result)) throw invalid();
        if (Long.valueOf(-1L).equals(result)) throw unavailable();
        throw unavailable();
    }

    public void release(String rawToken, String reservationId) {
        Long result = execute(releaseScript, List.of(key(rawToken)), reservationId);
        if (!Long.valueOf(1L).equals(result) && !Long.valueOf(0L).equals(result)) {
            throw unavailable();
        }
    }

    public boolean finalize(String rawToken, String reservationId) {
        Long result = execute(finalizeScript, List.of(key(rawToken)), reservationId);
        if (Long.valueOf(1L).equals(result)) return true;
        if (Long.valueOf(0L).equals(result)) return false;
        throw unavailable();
    }

    private Long execute(DefaultRedisScript<Long> script, List<String> keys, Object... arguments) {
        try {
            return redis.execute(script, keys, arguments);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private Map<Object, Object> entries(String key) {
        try {
            return redis.opsForHash().entries(key);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private String key(String rawToken) { return PetVerificationRedisKeys.token(hasher.token(rawToken)); }

    private Object[] arguments(Map<String, String> values, Duration ttl) {
        Object[] arguments = new Object[values.size() * 2 + 1];
        int index = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            arguments[index++] = entry.getKey();
            arguments[index++] = entry.getValue();
        }
        arguments[index] = Long.toString(ttl.toSeconds());
        return arguments;
    }

    private PetVerificationEvidence evidence(Map<Object, Object> values) {
        return new PetVerificationEvidence(
                PetVerificationProvider.valueOf(required(values, "provider")),
                required(values, "registrationNumberHmac"),
                optionalEnum(values, "deviceType", PetVerificationDeviceType.class),
                optional(values, "registeredName"),
                optionalDate(values, "birthDate"),
                optionalSex(values),
                optional(values, "breedName"),
                optionalBoolean(values, "neutered")
        );
    }

    private String required(Map<Object, Object> values, String key) {
        String value = optional(values, key); if (value == null) throw new IllegalArgumentException(); return value;
    }
    private String optional(Map<Object, Object> values, String key) {
        Object value = values.get(key); return value == null ? null : value.toString();
    }
    private <T extends Enum<T>> T optionalEnum(Map<Object, Object> values, String key, Class<T> type) {
        String value = optional(values, key); return value == null ? null : Enum.valueOf(type, value);
    }
    private LocalDate optionalDate(Map<Object, Object> values, String key) {
        String value = optional(values, key); return value == null ? null : LocalDate.parse(value);
    }
    private itda.pet.domain.PetSex optionalSex(Map<Object, Object> values) {
        String value = optional(values, "sex");
        if (value == null) return null;
        return switch (value) {
            case "MALE" -> itda.pet.domain.PetSex.MALE;
            case "FEMALE" -> itda.pet.domain.PetSex.FEMALE;
            default -> throw new IllegalArgumentException();
        };
    }
    private Boolean optionalBoolean(Map<Object, Object> values, String key) {
        String value = optional(values, key);
        if (value == null) return null;
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException();
        };
    }
    private void put(Map<String, String> map, String key, Object value) {
        if (value != null) map.put(key, value.toString());
    }
    private BusinessException invalid() { return new BusinessException(ErrorCode.PET_VERIFICATION_TOKEN_INVALID); }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.PET_VERIFICATION_UNAVAILABLE); }

    public record IssuedToken(String rawToken, Instant expiresAt) { }
    public record Reservation(String reservationId, PetVerificationEvidence evidence) { }
}
