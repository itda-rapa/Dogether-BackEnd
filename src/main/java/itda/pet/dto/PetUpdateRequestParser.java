package itda.pet.dto;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.PetSex;
import itda.pet.domain.PetSizeCode;
import itda.pet.dto.PetUpdateRequest.Field;
import itda.pet.service.PetUpdateCommand;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class PetUpdateRequestParser {

    private static final Map<String, Field> FIELDS_BY_JSON_NAME =
            Set.of(Field.values()).stream()
                    .collect(Collectors.toUnmodifiableMap(
                            Field::jsonName,
                            Function.identity()
                    ));

    private final Validator validator;

    public PetUpdateRequestParser(Validator validator) {
        this.validator = validator;
    }

    public PetUpdateCommand parse(JsonNode body) {
        PetUpdateRequest request = parseRequest(body);
        if (request.presentFields().isEmpty()) {
            throw validationFailed();
        }

        validatePresentProperties(request);
        return request.toCommand();
    }

    PetUpdateRequest parseRequest(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw validationFailed();
        }
        if (!FIELDS_BY_JSON_NAME.keySet().containsAll(body.propertyNames())) {
            throw validationFailed();
        }

        Set<Field> presentFields = EnumSet.noneOf(Field.class);
        for (String propertyName : body.propertyNames()) {
            presentFields.add(FIELDS_BY_JSON_NAME.get(propertyName));
        }

        return new PetUpdateRequest(
                presentFields,
                requiredString(body, Field.NICKNAME),
                nullableString(body, Field.BREED_NAME),
                nullableEnum(body, Field.SEX, PetSex.class),
                nullableBoolean(body, Field.NEUTERED),
                nullableDate(body, Field.BIRTH_DATE),
                nullableDecimal(body, Field.WEIGHT_KG),
                nullableEnum(body, Field.SIZE_CODE, PetSizeCode.class),
                nullableString(body, Field.BIO),
                requiredStringList(body, Field.PERSONALITY_TAGS),
                nullableString(body, Field.CARE_NOTE)
        );
    }

    private void validatePresentProperties(PetUpdateRequest request) {
        for (Field field : request.presentFields()) {
            if (!validator.validateProperty(request, field.jsonName())
                    .isEmpty()) {
                throw validationFailed();
            }
        }
    }

    private String requiredString(JsonNode body, Field field) {
        JsonNode value = valueIfPresent(body, field);
        if (value == null) {
            return null;
        }
        if (!value.isString()) {
            throw validationFailed();
        }
        return value.stringValue();
    }

    private String nullableString(JsonNode body, Field field) {
        JsonNode value = valueIfPresent(body, field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw validationFailed();
        }
        return value.stringValue();
    }

    private Boolean nullableBoolean(JsonNode body, Field field) {
        JsonNode value = valueIfPresent(body, field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isBoolean()) {
            throw validationFailed();
        }
        return value.booleanValue();
    }

    private LocalDate nullableDate(JsonNode body, Field field) {
        JsonNode value = valueIfPresent(body, field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw validationFailed();
        }
        try {
            return LocalDate.parse(value.stringValue());
        } catch (DateTimeParseException exception) {
            throw validationFailed();
        }
    }

    private BigDecimal nullableDecimal(JsonNode body, Field field) {
        JsonNode value = valueIfPresent(body, field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw validationFailed();
        }
        try {
            return value.asDecimal();
        } catch (ArithmeticException exception) {
            throw validationFailed();
        }
    }

    private <E extends Enum<E>> E nullableEnum(
            JsonNode body,
            Field field,
            Class<E> enumType
    ) {
        JsonNode value = valueIfPresent(body, field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw validationFailed();
        }
        try {
            return Enum.valueOf(enumType, value.stringValue());
        } catch (IllegalArgumentException exception) {
            throw validationFailed();
        }
    }

    private List<String> requiredStringList(JsonNode body, Field field) {
        JsonNode value = valueIfPresent(body, field);
        if (value == null) {
            return null;
        }
        if (!value.isArray()) {
            throw validationFailed();
        }

        List<String> values = new ArrayList<>();
        for (JsonNode element : value) {
            if (element.isNull()) {
                values.add(null);
            } else if (element.isString()) {
                values.add(element.stringValue());
            } else {
                throw validationFailed();
            }
        }
        return values;
    }

    private JsonNode valueIfPresent(JsonNode body, Field field) {
        return body.has(field.jsonName())
                ? body.get(field.jsonName())
                : null;
    }

    private BusinessException validationFailed() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
