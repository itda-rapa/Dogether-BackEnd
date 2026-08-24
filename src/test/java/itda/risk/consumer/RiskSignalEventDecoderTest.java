package itda.risk.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.risk.config.RiskSignalConsumerProperties;
import itda.risk.contract.RiskSignalEventV1;
import itda.risk.contract.RiskSignalType;
import itda.risk.contract.RiskSourceType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RiskSignalEventDecoderTest {
    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RiskSignalEventDecoder decoder;

    @BeforeEach
    void setUp() {
        decoder = new RiskSignalEventDecoder(
                objectMapper,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void decodesValidContract() throws Exception {
        RiskSignalEventV1 event = event(NOW.minusSeconds(10));

        RiskSignalEventV1 decoded = decoder.decode(
                "41", objectMapper.writeValueAsString(event.payload()));

        assertThat(decoded).isEqualTo(event);
    }

    @Test
    void rejectsUnknownScoreField() throws Exception {
        String payload = objectMapper.writeValueAsString(event(NOW).payload());
        String withScore = payload.substring(0, payload.length() - 1) + ",\"score\":100}";

        assertReason("41", withScore, RiskSignalContractException.Reason.MALFORMED_PAYLOAD);
    }

    @Test
    void rejectsWrongKafkaKey() throws Exception {
        assertReason("42", objectMapper.writeValueAsString(event(NOW).payload()),
                RiskSignalContractException.Reason.INVALID_KEY);
    }

    @Test
    void rejectsEventBeyondFutureSkew() throws Exception {
        assertReason("41", objectMapper.writeValueAsString(
                        event(NOW.plusSeconds(301)).payload()),
                RiskSignalContractException.Reason.FUTURE_OCCURRED_AT);
    }

    private void assertReason(
            String key, String payload, RiskSignalContractException.Reason reason
    ) {
        assertThatThrownBy(() -> decoder.decode(key, payload))
                .isInstanceOf(RiskSignalContractException.class)
                .extracting(error -> ((RiskSignalContractException) error).reason())
                .isEqualTo(reason);
    }

    private static RiskSignalEventV1 event(Instant occurredAt) {
        return new RiskSignalEventV1(
                1, UUID.fromString("96a0194e-31ab-4c8b-b9c6-d94d01884bea"),
                RiskSourceType.USER_BLOCK, 101L, RiskSignalType.USER_BLOCKED,
                41L, 42L, occurredAt, Map.of("reasonCode", "USER_REQUEST"));
    }

    private static RiskSignalConsumerProperties properties() {
        return new RiskSignalConsumerProperties(
                false, "test", 1, 100, 3, java.time.Duration.ofSeconds(1),
                "risk-signal-topic.DLT", java.time.Duration.ofSeconds(5),
                java.time.Duration.ofMinutes(5), java.time.Duration.ofDays(90));
    }
}
