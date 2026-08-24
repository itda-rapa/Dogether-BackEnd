package itda.risk.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import itda.risk.contract.RiskSignalEventV1;
import itda.risk.contract.RiskSignalType;
import itda.risk.contract.RiskSourceType;
import itda.risk.contract.RiskTopic;
import itda.risk.repository.RiskSignalEventJdbcRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Tag("kafka")
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {RiskTopic.RISK_SIGNAL_TOPIC, RiskTopic.RISK_SIGNAL_DLT_TOPIC},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration",
        "app.risk.outbox-relay.enabled=false",
        "app.risk.consumer.enabled=true",
        "app.risk.consumer.group-id=risk-consumer-integration",
        "app.risk.consumer.retry-backoff=10ms",
        "app.risk.consumer.dlt-topic=risk-signal-topic.DLT"
})
class RiskSignalConsumerKafkaIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired @Qualifier("riskKafkaTemplate") private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private RiskSignalEventJdbcRepository eventRepository;

    private Consumer<String, String> dltConsumer;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from risk_signal_events");
        Map<String, Object> properties = KafkaTestUtils.consumerProps(
                broker, "risk-dlt-" + UUID.randomUUID(), false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        dltConsumer = new DefaultKafkaConsumerFactory<>(
                properties, new StringDeserializer(), new StringDeserializer()).createConsumer();
        broker.consumeFromAnEmbeddedTopic(dltConsumer, RiskTopic.RISK_SIGNAL_DLT_TOPIC);
    }

    @AfterEach
    void tearDown() {
        dltConsumer.close();
        reset(eventRepository);
    }

    @Test
    void validEventIsPersistedOnceAcrossRedelivery() throws Exception {
        RiskSignalEventV1 event = event(UUID.randomUUID());
        RiskSignalEventV1 marker = event(UUID.randomUUID());
        String payload = objectMapper.writeValueAsString(event.payload());

        kafkaTemplate.send(RiskTopic.RISK_SIGNAL_TOPIC, "41", payload).get(5, TimeUnit.SECONDS);
        kafkaTemplate.send(RiskTopic.RISK_SIGNAL_TOPIC, "41", payload).get(5, TimeUnit.SECONDS);
        kafkaTemplate.send(RiskTopic.RISK_SIGNAL_TOPIC, "41",
                objectMapper.writeValueAsString(marker.payload())).get(5, TimeUnit.SECONDS);

        await(() -> countByEventId(marker.eventId()) == 1L);
        assertThat(countByEventId(event.eventId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "select score from risk_signal_events where event_id = ?",
                Integer.class, event.eventId())).isEqualTo(30);
    }

    @Test
    void invalidContractMovesSanitizedEnvelopeToDlt() throws Exception {
        String secretPayload = "{\"unexpectedSecret\":\"must-not-leak\"}";
        RecordMetadata source = kafkaTemplate.send(
                RiskTopic.RISK_SIGNAL_TOPIC, "41", secretPayload)
                .get(5, TimeUnit.SECONDS).getRecordMetadata();

        ConsumerRecord<String, String> record = awaitDlt(source.offset());

        assertThat(record.value()).contains("CONTRACT", "sourceTopic", "sourceOffset")
                .doesNotContain("must-not-leak", "unexpectedSecret");
        assertThat(new String(record.headers().lastHeader("dogether-dlt-reason").value(),
                java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("CONTRACT");
        assertThat(jdbc.queryForObject(
                "select count(*) from risk_signal_events", Long.class)).isZero();
    }

    @Test
    void transientDatabaseFailureRetriesAndStoresWithoutDlt() throws Exception {
        RiskSignalEventV1 event = event(UUID.randomUUID());
        doThrow(new TransientDataAccessResourceException("test transient failure"))
                .doCallRealMethod()
                .when(eventRepository).insertIfAbsent(any(), any());

        kafkaTemplate.send(RiskTopic.RISK_SIGNAL_TOPIC, "41",
                objectMapper.writeValueAsString(event.payload())).get(5, TimeUnit.SECONDS);

        await(() -> countByEventId(event.eventId()) == 1L);
        verify(eventRepository, atLeast(2)).insertIfAbsent(any(), any());
    }

    @Test
    void exhaustedDatabaseRetriesPreserveOriginalDltRecord() throws Exception {
        RiskSignalEventV1 event = event(UUID.randomUUID());
        String payload = objectMapper.writeValueAsString(event.payload());
        doThrow(new TransientDataAccessResourceException("test persistent failure"))
                .when(eventRepository).insertIfAbsent(any(), any());

        RecordMetadata source = kafkaTemplate.send(
                RiskTopic.RISK_SIGNAL_TOPIC, "41", payload)
                .get(5, TimeUnit.SECONDS).getRecordMetadata();
        ConsumerRecord<String, String> dlt = awaitDlt(source.offset());

        assertThat(dlt.key()).isEqualTo("41");
        assertThat(dlt.partition()).isEqualTo(source.partition());
        assertThat(dlt.value()).isEqualTo(payload);
        assertThat(header(dlt, "dogether-dlt-reason")).isEqualTo("RETRY_EXHAUSTED");
        assertThat(countByEventId(event.eventId())).isZero();
        verify(eventRepository, atLeast(3)).insertIfAbsent(any(), any());
    }

    private ConsumerRecord<String, String> awaitDlt(long sourceOffset) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : dltConsumer.poll(Duration.ofMillis(200))) {
                if (Long.toString(sourceOffset).equals(
                        header(record, "dogether-dlt-source-offset"))) {
                    return record;
                }
            }
        }
        throw new AssertionError("Timed out waiting for DLT source offset " + sourceOffset);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(
                header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private long countByEventId(UUID eventId) {
        return jdbc.queryForObject(
                "select count(*) from risk_signal_events where event_id = ?", Long.class, eventId);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static RiskSignalEventV1 event(UUID eventId) {
        return new RiskSignalEventV1(
                1, eventId, RiskSourceType.USER_BLOCK, 601L,
                RiskSignalType.USER_BLOCKED, 41L, 42L, Instant.now().minusSeconds(1),
                Map.of("reasonCode", "USER_REQUEST"));
    }
}
