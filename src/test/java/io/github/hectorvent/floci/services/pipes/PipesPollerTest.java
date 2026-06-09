package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamService;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.vertx.core.Vertx;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipesPollerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private Vertx vertx;
    @Mock private SqsService sqsService;
    @Mock private KinesisService kinesisService;
    @Mock private DynamoDbStreamService dynamoDbStreamService;
    @Mock private PipesKafkaConsumerManager kafkaConsumerManager;
    @Mock private PipesTargetInvoker targetInvoker;
    @Mock private EmulatorConfig config;

    private PipesPoller poller;

    @BeforeEach
    void setUp() {
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        poller = new PipesPoller(vertx, sqsService, kinesisService, dynamoDbStreamService,
                kafkaConsumerManager, targetInvoker, new PipesFilterMatcher(MAPPER), MAPPER, config);
    }

    @Test
    void pollKafka_filtersUsingDecodedPayloadButDeliversOriginalRecord() throws Exception {
        Pipe pipe = selfManagedKafkaPipe();
        byte[] key = "customer-123".getBytes(StandardCharsets.UTF_8);
        byte[] value = "{\"status\":\"active\",\"id\":\"order-1\"}".getBytes(StandardCharsets.UTF_8);
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>("orders", 0, 42L, key, value);

        when(kafkaConsumerManager.poll(pipe)).thenReturn(records(record));
        when(kafkaConsumerManager.resolveBootstrapServers(pipe)).thenReturn("broker-1:9092");

        poller.pollKafka(pipe, "us-east-1");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(targetInvoker).invoke(eq(pipe), payloadCaptor.capture(), eq("us-east-1"));
        verify(kafkaConsumerManager).commit(eq(pipe), anyMap());

        JsonNode delivered = MAPPER.readTree(payloadCaptor.getValue());
        assertEquals("orders", delivered.path("topic").asText());
        assertTrue(delivered.path("partition").isInt());
        assertEquals("broker-1:9092", delivered.path("bootstrapServers").asText());
        assertEquals(Base64.getEncoder().encodeToString(key), delivered.path("key").asText());
        assertEquals(Base64.getEncoder().encodeToString(value), delivered.path("value").asText());
        assertTrue(delivered.path("value").isTextual());
    }

    @Test
    void pollKafka_doesNotCommitWhenDeliveryFails() throws Exception {
        Pipe pipe = selfManagedKafkaPipe();
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
                "orders", 0, 7L, null, "{\"status\":\"active\"}".getBytes(StandardCharsets.UTF_8));

        when(kafkaConsumerManager.poll(pipe)).thenReturn(records(record));
        when(kafkaConsumerManager.resolveBootstrapServers(pipe)).thenReturn("broker-1:9092");
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(targetInvoker).invoke(eq(pipe), anyString(), eq("us-east-1"));

        poller.pollKafka(pipe, "us-east-1");

        verify(kafkaConsumerManager, never()).commit(pipe);
        verify(kafkaConsumerManager, never()).commit(eq(pipe), anyMap());
    }

    @Test
    void pollKafka_commitsDeliveredPrefixWhenLaterRecordFails() throws Exception {
        Pipe pipe = selfManagedKafkaPipe();
        ConsumerRecord<byte[], byte[]> first = new ConsumerRecord<>(
                "orders", 0, 0L, null, "{\"status\":\"active\",\"id\":\"order-1\"}".getBytes(StandardCharsets.UTF_8));
        ConsumerRecord<byte[], byte[]> second = new ConsumerRecord<>(
                "orders", 0, 1L, null, "{\"status\":\"active\",\"id\":\"order-2\"}".getBytes(StandardCharsets.UTF_8));

        when(kafkaConsumerManager.poll(pipe)).thenReturn(records(first, second));
        when(kafkaConsumerManager.resolveBootstrapServers(pipe)).thenReturn("broker-1:9092");
        org.mockito.Mockito.doNothing()
                .doThrow(new RuntimeException("boom"))
                .when(targetInvoker).invoke(eq(pipe), anyString(), eq("us-east-1"));

        poller.pollKafka(pipe, "us-east-1");

        ArgumentCaptor<Map<TopicPartition, OffsetAndMetadata>> offsetsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kafkaConsumerManager).commit(eq(pipe), offsetsCaptor.capture());
        assertEquals(1L, offsetsCaptor.getValue().get(new TopicPartition("orders", 0)).offset());
    }

    private Pipe selfManagedKafkaPipe() throws Exception {
        Pipe pipe = new Pipe();
        pipe.setName("orders-pipe");
        pipe.setArn("arn:aws:pipes:us-east-1:000000000000:pipe/orders-pipe");
        pipe.setSource("smk://broker-1:9092");
        pipe.setTarget("arn:aws:sqs:us-east-1:000000000000:orders-target");
        pipe.setSourceParameters(MAPPER.readTree("""
                {
                  "SelfManagedKafkaParameters": {
                    "TopicName": "orders"
                  },
                  "FilterCriteria": {
                    "Filters": [
                      {"Pattern": "{\\\"value\\\": {\\\"status\\\": [\\\"active\\\"]}}"}
                    ]
                  }
                }
                """));
        return pipe;
    }

    private ConsumerRecords<byte[], byte[]> records(ConsumerRecord<byte[], byte[]>... records) {
        Map<TopicPartition, List<ConsumerRecord<byte[], byte[]>>> byPartition = new java.util.LinkedHashMap<>();
        for (ConsumerRecord<byte[], byte[]> record : records) {
            TopicPartition topicPartition = new TopicPartition(record.topic(), record.partition());
            byPartition.computeIfAbsent(topicPartition, ignored -> new java.util.ArrayList<>()).add(record);
        }
        return new ConsumerRecords<>(byPartition);
    }
}
