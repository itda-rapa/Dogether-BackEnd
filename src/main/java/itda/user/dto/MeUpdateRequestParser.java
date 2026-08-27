package itda.user.dto;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.math.BigDecimal;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class MeUpdateRequestParser {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "nickname",
            "neighborhoodCode",
            "weightKg"
    );
    private static final BigDecimal MIN_WEIGHT_KG = new BigDecimal("1.00");
    private static final BigDecimal MAX_WEIGHT_KG = new BigDecimal("500.00");

    public MeUpdateCommand parse(JsonNode body) {
        if (body == null || !body.isObject()
                || body.propertyNames().isEmpty()
                || !ALLOWED_FIELDS.containsAll(body.propertyNames())) {
            throw validationFailed();
        }

        boolean nicknamePresent = body.has("nickname");
        String nickname = nicknamePresent
                ? requiredTrimmedString(body.get("nickname")) : null;
        if (nicknamePresent && (nickname.isBlank()
                || nickname.length() < 2 || nickname.length() > 20)) {
            throw validationFailed();
        }

        boolean neighborhoodCodePresent = body.has("neighborhoodCode");
        String neighborhoodCode = neighborhoodCodePresent
                ? requiredTrimmedNeighborhoodCode(body.get("neighborhoodCode"))
                : null;

        boolean weightKgPresent = body.has("weightKg");
        BigDecimal weightKg = weightKgPresent
                ? nullableWeightKg(body.get("weightKg")) : null;

        return new MeUpdateCommand(
                nicknamePresent,
                nickname,
                neighborhoodCodePresent,
                neighborhoodCode,
                weightKgPresent,
                weightKg
        );
    }

    private String requiredTrimmedString(JsonNode value) {
        if (value == null || value.isNull() || !value.isString()) {
            throw validationFailed();
        }
        return value.stringValue().trim();
    }

    private String requiredTrimmedNeighborhoodCode(JsonNode value) {
        String neighborhoodCode = requiredTrimmedString(value);
        if (neighborhoodCode.isBlank() || neighborhoodCode.length() > 20) {
            throw validationFailed();
        }
        return neighborhoodCode;
    }

    private BigDecimal nullableWeightKg(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw validationFailed();
        }
        try {
            BigDecimal weightKg = value.asDecimal();
            if (weightKg.scale() > 2
                    || weightKg.compareTo(MIN_WEIGHT_KG) < 0
                    || weightKg.compareTo(MAX_WEIGHT_KG) > 0) {
                throw validationFailed();
            }
            return weightKg;
        } catch (ArithmeticException exception) {
            throw validationFailed();
        }
    }

    private BusinessException validationFailed() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
