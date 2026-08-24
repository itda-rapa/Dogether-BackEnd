package itda.risk.service;

import static org.assertj.core.api.Assertions.assertThat;

import itda.risk.contract.RiskSignalEventV1;
import itda.risk.contract.RiskSignalType;
import itda.risk.contract.RiskSourceEventCommand;
import itda.risk.contract.RiskSourceEventPublisher;
import itda.risk.contract.RiskSourceType;
import itda.risk.contract.RiskTopic;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Tag("kafka")
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = RiskTopic.RISK_SIGNAL_TOPIC,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration",
        "app.risk.outbox-relay.enabled=false"
})
class RiskSignalOutboxRelayKafkaIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private RiskSourceEventPublisher sourcePublisher;
    @Autowired private RiskSignalOutboxRelayWorker worker;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private ObjectMapper objectMapper;

    private Consumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from risk_signal_outbox");
        Map<String, Object> properties = KafkaTestUtils.consumerProps(
                broker, "risk-relay-" + UUID.randomUUID(), false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new DefaultKafkaConsumerFactory<>(
                properties, new StringDeserializer(), new StringDeserializer()).createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, RiskTopic.RISK_SIGNAL_TOPIC);
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    @Test
    void relayPublishesOriginalEventWithActorKeyAndMarksOutboxSent() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> sourcePublisher.enqueue(
                new RiskSourceEventCommand(
                        RiskSourceType.USER_BLOCK,
                        301L,
                        RiskSignalType.USER_BLOCKED,
                        41L,
                        42L,
                        Instant.now().minusSeconds(30),
                        Map.of("reasonCode", "USER_REQUEST"))));

        RiskSignalOutboxRelayWorker.Result result = worker.runOnce();
        ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(
                consumer, RiskTopic.RISK_SIGNAL_TOPIC, Duration.ofSeconds(10));
        RiskSignalEventV1 event = objectMapper.readValue(record.value(), RiskSignalEventV1.class);

        assertThat(result).isEqualTo(new RiskSignalOutboxRelayWorker.Result(1, 0, 0, 0));
        assertThat(record.topic()).isEqualTo(RiskTopic.RISK_SIGNAL_TOPIC);
        assertThat(record.key()).isEqualTo("41");
        assertThat(event.schemaVersion()).isEqualTo(RiskSignalEventV1.SCHEMA_VERSION);
        assertThat(event.eventId()).isNotNull();
        assertThat(event.sourceType()).isEqualTo(RiskSourceType.USER_BLOCK);
        assertThat(event.sourceId()).isEqualTo(301L);
        assertThat(event.actorUserId()).isEqualTo(41L);
        assertThat(event.targetUserId()).isEqualTo(42L);
        assertThat(event.signalType()).isEqualTo(RiskSignalType.USER_BLOCKED);
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.metadata()).containsEntry("reasonCode", "USER_REQUEST");
        assertThat(objectMapper.readTree(record.value())).isEqualTo(objectMapper.readTree(
                jdbc.queryForObject(
                        "select payload::text from risk_signal_outbox where source_id = 301", String.class)));
        assertThat(jdbc.queryForObject(
                "select status from risk_signal_outbox where source_id = 301", String.class))
                .isEqualTo("SENT");
        assertThat(jdbc.queryForObject(
                "select published_at is not null from risk_signal_outbox where source_id = 301", Boolean.class))
                .isTrue();
    }
}
