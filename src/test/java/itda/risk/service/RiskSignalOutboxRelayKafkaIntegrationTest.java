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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@Tag("kafka")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration",
        "app.risk.outbox-relay.enabled=false",
        "app.risk.outbox-relay.lease=10s",
        "app.risk.outbox-relay.send-timeout=2s",
        "app.risk.outbox-relay.max-block=1s",
        "app.risk.outbox-relay.base-backoff=5s",
        "app.risk.outbox-relay.max-backoff=5s"
})
class RiskSignalOutboxRelayKafkaIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                        .asCompatibleSubstituteFor("postgres")
        );

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka-native:3.8.0");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired private RiskSourceEventPublisher sourcePublisher;
    @Autowired private RiskSignalOutboxRelayWorker worker;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;
    @Autowired @Qualifier("riskKafkaTemplate") private KafkaTemplate<String, String> kafkaTemplate;

    private Consumer<String, String> consumer;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from risk_signal_outbox");
        createTopicIfMissing();
        Map<String, Object> properties = consumerProperties();
        consumer = new DefaultKafkaConsumerFactory<>(
                properties, new StringDeserializer(), new StringDeserializer()).createConsumer();
        consumer.subscribe(List.of(RiskTopic.RISK_SIGNAL_TOPIC));
        kafkaTemplate.send(RiskTopic.RISK_SIGNAL_TOPIC, "warmup", "{}")
                .get(10, TimeUnit.SECONDS);
        moveConsumerToCurrentEnd();
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

    @Test
    void brokerOutageRetriesAndRepublishesSameEventAfterRecovery() throws Exception {
        enqueue(302L);
        UUID eventId = jdbc.queryForObject(
                "select event_id from risk_signal_outbox where source_id = 302", UUID.class);

        pauseKafka();
        RiskSignalOutboxRelayWorker.Result failed;
        try {
            failed = worker.runOnce();
        } finally {
            resumeKafka();
        }

        assertThat(failed).isEqualTo(new RiskSignalOutboxRelayWorker.Result(0, 1, 0, 0));
        assertThat(jdbc.queryForObject("""
                select status = 'RETRY'
                   and attempts = 1
                   and next_retry_at > updated_at
                   and last_error like 'kafka publish failed:%'
                  from risk_signal_outbox
                 where source_id = 302
                """, Boolean.class)).isTrue();

        jdbc.update("""
                update risk_signal_outbox
                   set next_retry_at = now() - interval '1 second'
                 where source_id = 302
                """);

        RiskSignalOutboxRelayWorker.Result recovered = worker.runOnce();
        var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), 2);
        List<RiskSignalEventV1> republished = new ArrayList<>();
        for (ConsumerRecord<String, String> record : records.records(RiskTopic.RISK_SIGNAL_TOPIC)) {
            assertThat(record.key()).isEqualTo("41");
            republished.add(objectMapper.readValue(record.value(), RiskSignalEventV1.class));
        }

        assertThat(recovered).isEqualTo(new RiskSignalOutboxRelayWorker.Result(1, 0, 0, 0));
        assertThat(republished)
                .hasSize(2)
                .extracting(RiskSignalEventV1::eventId)
                .containsOnly(eventId);
        assertThat(jdbc.queryForObject("""
                select status = 'SENT'
                   and attempts = 2
                   and published_at is not null
                   and last_error is null
                  from risk_signal_outbox
                 where source_id = 302
                """, Boolean.class)).isTrue();
    }

    private void enqueue(long sourceId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> sourcePublisher.enqueue(
                new RiskSourceEventCommand(
                        RiskSourceType.USER_BLOCK,
                        sourceId,
                        RiskSignalType.USER_BLOCKED,
                        41L,
                        42L,
                        Instant.now().minusSeconds(30),
                        Map.of("reasonCode", "USER_REQUEST"))));
    }

    private static Map<String, Object> consumerProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "risk-relay-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return properties;
    }

    private static void createTopicIfMissing() throws Exception {
        Map<String, Object> properties = Map.of(
                CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(properties)) {
            if (!admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(RiskTopic.RISK_SIGNAL_TOPIC)) {
                admin.createTopics(List.of(new NewTopic(RiskTopic.RISK_SIGNAL_TOPIC, 1, (short) 1)))
                        .all().get(10, TimeUnit.SECONDS);
            }
        }
    }

    private void moveConsumerToCurrentEnd() {
        Instant deadline = Instant.now().plusSeconds(10);
        while (consumer.assignment().isEmpty() && Instant.now().isBefore(deadline)) {
            consumer.poll(Duration.ofMillis(100));
        }
        assertThat(consumer.assignment()).isNotEmpty();
        consumer.seekToEnd(consumer.assignment());
        consumer.assignment().forEach(consumer::position);
    }

    private static void pauseKafka() {
        DockerClientFactory.instance().client()
                .pauseContainerCmd(kafka.getContainerId()).exec();
    }

    private static void resumeKafka() {
        DockerClientFactory.instance().client()
                .unpauseContainerCmd(kafka.getContainerId()).exec();
    }
}
