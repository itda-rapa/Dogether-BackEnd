package itda.risk.config;

import io.micrometer.core.instrument.MeterRegistry;
import itda.risk.consumer.RiskSignalConsumerMetrics;
import itda.risk.consumer.RiskSignalContractException;
import itda.risk.consumer.RiskSignalDeadLetterPublisher;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(prefix = "app.risk.consumer", name = "enabled", havingValue = "true")
public class RiskKafkaConsumerConfig {
    @Bean("riskConsumerFactory")
    ConsumerFactory<String, String> riskConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            RiskSignalConsumerProperties properties,
            MeterRegistry meterRegistry
    ) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.groupId());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, properties.maxPollRecords());
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        DefaultKafkaConsumerFactory<String, String> factory =
                new DefaultKafkaConsumerFactory<>(config);
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
    }

    @Bean("riskConsumerErrorHandler")
    DefaultErrorHandler riskConsumerErrorHandler(
            RiskSignalDeadLetterPublisher deadLetterPublisher,
            RiskSignalConsumerProperties properties,
            RiskSignalConsumerMetrics metrics
    ) {
        long retries = properties.maxAttempts() - 1L;
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                deadLetterPublisher,
                new FixedBackOff(properties.retryBackoff().toMillis(), retries));
        errorHandler.addNotRetryableExceptions(RiskSignalContractException.class);
        errorHandler.setCommitRecovered(true);
        errorHandler.setAckAfterHandle(true);
        errorHandler.setLogLevel(KafkaException.Level.TRACE);
        errorHandler.setRetryListeners((record, exception, deliveryAttempt) ->
                metrics.recordRetry(exception, deliveryAttempt));
        return errorHandler;
    }

    @Bean("riskKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<String, String> riskKafkaListenerContainerFactory(
            @Qualifier("riskConsumerFactory") ConsumerFactory<String, String> consumerFactory,
            @Qualifier("riskConsumerErrorHandler") DefaultErrorHandler errorHandler,
            RiskSignalConsumerProperties properties
    ) {
        KafkaUtils.setConsumerRecordFormatter(record -> "ConsumerRecord(topic="
                + record.topic() + ", partition=" + record.partition()
                + ", offset=" + record.offset() + ")");
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(properties.concurrency());
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
