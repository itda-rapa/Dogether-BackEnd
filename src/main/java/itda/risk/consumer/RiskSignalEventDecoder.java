package itda.risk.consumer;

import itda.risk.config.RiskSignalConsumerProperties;
import itda.risk.contract.RiskSignalEventV1;
import itda.risk.contract.RiskTopic;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
public class RiskSignalEventDecoder {
    private final RiskSignalConsumerProperties properties;
    private final Clock clock;
    private final ObjectReader reader;

    @Autowired
    public RiskSignalEventDecoder(
            ObjectMapper objectMapper,
            RiskSignalConsumerProperties properties
    ) {
        this(objectMapper, properties, Clock.systemUTC());
    }

    RiskSignalEventDecoder(
            ObjectMapper objectMapper,
            RiskSignalConsumerProperties properties,
            Clock clock
    ) {
        this.properties = properties;
        this.clock = clock;
        this.reader = objectMapper.readerFor(RiskSignalEventV1.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public RiskSignalEventV1 decode(String key, String payload) {
        if (payload == null || payload.isBlank()) {
            throw new RiskSignalContractException(
                    RiskSignalContractException.Reason.MALFORMED_PAYLOAD);
        }

        RiskSignalEventV1 event;
        try {
            event = reader.readValue(payload);
        } catch (JacksonException exception) {
            throw new RiskSignalContractException(
                    RiskSignalContractException.Reason.MALFORMED_PAYLOAD);
        } catch (IllegalArgumentException exception) {
            throw new RiskSignalContractException(
                    RiskSignalContractException.Reason.INVALID_CONTRACT);
        }

        if (!RiskTopic.keyFor(event).equals(key)) {
            throw new RiskSignalContractException(RiskSignalContractException.Reason.INVALID_KEY);
        }
        Instant latestAllowed = clock.instant().plus(properties.maxFutureSkew());
        if (event.occurredAt().isAfter(latestAllowed)) {
            throw new RiskSignalContractException(
                    RiskSignalContractException.Reason.FUTURE_OCCURRED_AT);
        }
        return event;
    }
}
