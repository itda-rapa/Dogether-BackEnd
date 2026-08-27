package itda.auth.dto;

import java.math.BigDecimal;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.exc.MismatchedInputException;

/** Accepts JSON numeric tokens only, preserving their decimal scale for validation. */
public class StrictBigDecimalDeserializer extends StdDeserializer<BigDecimal> {

    public StrictBigDecimalDeserializer() {
        super(BigDecimal.class);
    }

    @Override
    public BigDecimal deserialize(JsonParser parser, DeserializationContext context)
            throws JacksonException {
        JsonToken token = parser.currentToken();
        if (token != JsonToken.VALUE_NUMBER_INT && token != JsonToken.VALUE_NUMBER_FLOAT) {
            throw MismatchedInputException.from(
                    parser,
                    BigDecimal.class,
                    "weightKg는 JSON 숫자여야 합니다."
            );
        }
        return parser.getDecimalValue();
    }
}
