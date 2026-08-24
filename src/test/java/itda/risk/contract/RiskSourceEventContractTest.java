package itda.risk.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RiskSourceEventContractTest {
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-24T09:30:00Z");

    @Test
    void createsVersionOneEventWithCamelCaseJsonPayload() throws Exception {
        RiskSignalEventV1 event = RiskSignalEventV1.from(UUID.fromString("cd878e27-3280-4bc5-a691-fa24e0d1a8de"), command());
        ObjectMapper objectMapper = new ObjectMapper();

        String json = objectMapper.writeValueAsString(event);
        RiskSignalEventV1 restored = objectMapper.readValue(json, RiskSignalEventV1.class);

        assertThat(json).contains("\"schemaVersion\":1");
        assertThat(json).contains("\"eventId\":\"cd878e27-3280-4bc5-a691-fa24e0d1a8de\"");
        assertThat(json).contains("\"sourceType\":\"USER_BLOCK\"");
        assertThat(json).contains("\"occurredAt\":\"2026-08-24T09:30:00Z\"");
        assertThat(restored).isEqualTo(event);
        assertThat(RiskTopic.keyFor(event)).isEqualTo("41");
    }

    @Test
    void metadataIsImmutableAndRejectsUnknownOrSensitiveValues() {
        RiskSourceEventCommand command = command();

        assertThatThrownBy(() -> command.metadata().put("reasonCode", "other"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new RiskSourceEventCommand(
                RiskSourceType.USER_BLOCK, 81L, RiskSignalType.USER_BLOCKED,
                41L, 42L, OCCURRED_AT, Map.of("note", "user@example.com")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported metadata field");
        assertThatThrownBy(() -> new RiskSourceEventCommand(
                RiskSourceType.USER_BLOCK, 81L, RiskSignalType.USER_BLOCKED,
                41L, 42L, OCCURRED_AT, Map.of("reasonCode", "user@example.com")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-sensitive uppercase code");
        assertThatThrownBy(() -> new RiskSourceEventCommand(
                RiskSourceType.GREETING, 82L, RiskSignalType.GREETING_EXPIRED,
                41L, 42L, OCCURRED_AT, Map.of("ttlHours", "37.123,127.123")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("integer");
    }

    @Test
    void rejectsUnsupportedSourceAndSignalCombinations() {
        assertThatThrownBy(() -> new RiskSourceEventCommand(
                RiskSourceType.USER_BLOCK, 81L, RiskSignalType.GREETING_EXPIRED,
                41L, 42L, OCCURRED_AT, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported source/signal combination");
        assertThatThrownBy(() -> new RiskSourceEventCommand(
                RiskSourceType.GREETING, 82L, RiskSignalType.USER_BLOCKED,
                41L, 42L, OCCURRED_AT, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported source/signal combination");
        assertThatThrownBy(() -> new RiskSignalEventV1(
                1, UUID.randomUUID(), RiskSourceType.USER_BLOCK, 81L,
                RiskSignalType.GREETING_EXPIRED, 41L, 42L, OCCURRED_AT, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported source/signal combination");
    }

    @Test
    void acceptsOnlyDefinedMetadataForEachSignal() {
        RiskSourceEventCommand block = command();
        RiskSourceEventCommand greeting = new RiskSourceEventCommand(
                RiskSourceType.GREETING, 82L, RiskSignalType.GREETING_EXPIRED,
                41L, 42L, OCCURRED_AT, Map.of("ttlHours", "24"));

        assertThat(block.metadata()).containsExactly(Map.entry("reasonCode", "USER_REQUEST"));
        assertThat(greeting.metadata()).containsExactly(Map.entry("ttlHours", "24"));
    }

    @Test
    void rejectsNonPositiveIdentifiers() {
        assertThatThrownBy(() -> new RiskSourceEventCommand(
                RiskSourceType.GREETING, 0L, RiskSignalType.GREETING_EXPIRED,
                41L, 42L, OCCURRED_AT, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceId");
    }

    private static RiskSourceEventCommand command() {
        return new RiskSourceEventCommand(
                RiskSourceType.USER_BLOCK, 81L, RiskSignalType.USER_BLOCKED,
                41L, 42L, OCCURRED_AT, Map.of("reasonCode", "USER_REQUEST"));
    }
}
