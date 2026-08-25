package itda.pet.support;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class IfMatchVersionParser {

    public long parse(List<String> headerValues) {
        if (headerValues == null || headerValues.size() != 1) {
            throw validationFailed();
        }

        String value = trimHttpOws(headerValues.getFirst());
        if (value.length() < 3
                || value.charAt(0) != '"'
                || value.charAt(value.length() - 1) != '"') {
            throw validationFailed();
        }

        String numericValue = value.substring(1, value.length() - 1);
        for (int index = 0; index < numericValue.length(); index++) {
            char character = numericValue.charAt(index);
            if (character < '0' || character > '9') {
                throw validationFailed();
            }
        }

        try {
            return Long.parseLong(numericValue);
        } catch (NumberFormatException exception) {
            throw validationFailed();
        }
    }

    private String trimHttpOws(String value) {
        if (value == null) {
            throw validationFailed();
        }

        int start = 0;
        int end = value.length();
        while (start < end && isHttpOws(value.charAt(start))) {
            start++;
        }
        while (end > start && isHttpOws(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private boolean isHttpOws(char character) {
        return character == ' ' || character == '\t';
    }

    private BusinessException validationFailed() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
