package itda.setlog.dto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum SetlogSource {
    SEED,
    USER;

    @JsonValue
    public String jsonValue() {
        return name();
    }
}
