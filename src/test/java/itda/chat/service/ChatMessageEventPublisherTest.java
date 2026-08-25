package itda.chat.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import itda.chat.dto.response.ChatMessageResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

class ChatMessageEventPublisherTest {

    @Test
    void committedMessageDoesNotBecomeHttpFailureWhenKafkaSendFailsSynchronously()
            throws Exception {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(org.mockito.ArgumentMatchers.any()))
                .thenReturn("{}");
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new KafkaException("broker unavailable"));

        ChatMessageEventPublisher publisher = new ChatMessageEventPublisher(
                kafkaTemplate,
                objectMapper,
                Runnable::run
        );
        ChatMessageResponse message = new ChatMessageResponse(
                1L,
                3L,
                "PET",
                7L,
                "Dogether",
                "TEXT",
                "hello",
                null,
                "client-message-id",
                Instant.parse("2026-08-13T18:20:58Z")
        );

        assertThatCode(() -> publisher.publishAfterCommit(message))
                .doesNotThrowAnyException();
    }
}
