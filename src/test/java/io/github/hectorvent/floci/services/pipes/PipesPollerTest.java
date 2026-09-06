package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamService;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
        lenient().when(kafkaConsumerManager.resolveBatchSize(any(), anyInt())).thenReturn(10);
        poller = new PipesPoller(vertx, sqsService, kinesisService, dynamoDbStreamService,
                kafkaConsumerManager, targetInvoker, new PipesFilterMatcher(MAPPER), MAPPER, config);
    }

    @Test
    void asEventArrayWrapsSingleObjectInBatchArray() throws Exception {
        // Pipes delivers events to a target as a batch array; a single-object enrichment response
        // must become a one-element array so a target like "InputPath": "$.[0]" can unwrap it.
        String wrapped = PipesPoller.asEventArray(MAPPER, "{\"systemId\":\"S1\",\"solutions\":[1]}");
        JsonNode node = MAPPER.readTree(wrapped);
        assertTrue(node.isArray());
        assertEquals(1, node.size());
        assertEquals("S1", node.get(0).path("systemId").asText());
    }

    @Test
    void asEventArrayLeavesArrayResponseUnchanged() throws Exception {
        String out = PipesPoller.asEventArray(MAPPER, "[{\"a\":1},{\"a\":2}]");
        JsonNode node = MAPPER.readTree(out);
        assertTrue(node.isArray());
        assertEquals(2, node.size());
        assertEquals(2, node.get(1).path("a").asInt());
    }

    @Test
    void asEventArrayWrapsNonJsonAsSingleStringEvent() throws Exception {
        String out = PipesPoller.asEventArray(MAPPER, "not-json");
        JsonNode node = MAPPER.readTree(out);
        assertTrue(node.isArray());
        assertEquals(1, node.size());
        assertEquals("not-json", node.get(0).asText());
    }

    @Test
    void pollSqs_enrichmentToNonLambdaTargetForwardsRawResponse() throws Exception {
        // A non-Lambda target (here Step Functions) must receive the raw enrichment response, not a
        // one-element batch array — array-wrapping would start the execution with [{...}] instead of {...}.
        Pipe pipe = new Pipe();
        pipe.setName("enrich-sfn");
        pipe.setSource("arn:aws:sqs:us-east-1:000000000000:src-queue");
        pipe.setEnrichment("arn:aws:lambda:us-east-1:000000000000:function:enrich");
        pipe.setTarget("arn:aws:states:us-east-1:000000000000:stateMachine:tgt");

        Message msg = new Message("{\"orderId\":\"o1\"}");
        msg.setMessageId("m1");
        msg.setReceiptHandle("rh1");
        when(sqsService.receiveMessage(anyString(), anyInt(), anyInt(), anyInt(), eq("us-east-1")))
                .thenReturn(List.of(msg));
        when(targetInvoker.applyEnrichment(eq(pipe), anyString(), eq("us-east-1")))
                .thenReturn("{\"systemId\":\"S1\"}");

        poller.pollSqs(pipe, "us-east-1");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(targetInvoker).invoke(eq(pipe), payload.capture(), eq("us-east-1"));
        JsonNode delivered = MAPPER.readTree(payload.getValue());
        assertFalse(delivered.isArray(),
                "non-Lambda target must receive the raw enrichment response, not a batch array");
        assertEquals("S1", delivered.path("systemId").asText());
    }

    @Test
    void pollSqs_enrichmentToLambdaTargetWrapsResponseInBatchArray() throws Exception {
        // A Lambda target expects the SQSRecord[]-style batch, so a single-object enrichment response
        // is wrapped in a one-element array.
        Pipe pipe = new Pipe();
        pipe.setName("enrich-lambda");
        pipe.setSource("arn:aws:sqs:us-east-1:000000000000:src-queue");
        pipe.setEnrichment("arn:aws:lambda:us-east-1:000000000000:function:enrich");
        pipe.setTarget("arn:aws:lambda:us-east-1:000000000000:function:tgt");

        Message msg = new Message("{\"orderId\":\"o1\"}");
        msg.setMessageId("m1");
        msg.setReceiptHandle("rh1");
        when(sqsService.receiveMessage(anyString(), anyInt(), anyInt(), anyInt(), eq("us-east-1")))
                .thenReturn(List.of(msg));
        when(targetInvoker.applyEnrichment(eq(pipe), anyString(), eq("us-east-1")))
                .thenReturn("{\"systemId\":\"S1\"}");

        poller.pollSqs(pipe, "us-east-1");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(targetInvoker).invoke(eq(pipe), payload.capture(), eq("us-east-1"));
        JsonNode delivered = MAPPER.readTree(payload.getValue());
        assertTrue(delivered.isArray(), "Lambda target must receive the batch array shape");
        assertEquals(1, delivered.size());
        assertEquals("S1", delivered.get(0).path("systemId").asText());
    }

    @Test
    void pollKafka_filtersUsingDecodedPayloadButDeliversOriginalRecord() throws Exception {
        Pipe pipe = selfManagedKafkaPipe();
        byte[] key = "customer-123".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] value = "{\"status\":\"active\",\"id\":\"order-1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] traceId = "abc123".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        KafkaRecordDto record = kafkaRecord("orders", 0, 42L, key, value, new KafkaHeaderDto("traceId", traceId));

        when(kafkaConsumerManager.poll(pipe)).thenReturn(List.of(record));
        when(kafkaConsumerManager.resolveBootstrapServers(pipe)).thenReturn("broker-1:9092");

        poller.pollKafka(pipe, "us-east-1");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(targetInvoker).invoke(eq(pipe), payloadCaptor.capture(), eq("us-east-1"));
        verify(kafkaConsumerManager).commit(eq(pipe), anyList());

        JsonNode delivered = MAPPER.readTree(payloadCaptor.getValue());
        assertEquals("orders", delivered.path("topic").asText());
        assertTrue(delivered.path("partition").isInt());
        assertEquals("broker-1:9092", delivered.path("bootstrapServers").asText());
        assertEquals("Y3VzdG9tZXItMTIz", delivered.path("key").asText());
        assertEquals("eyJzdGF0dXMiOiJhY3RpdmUiLCJpZCI6Im9yZGVyLTEifQ==", delivered.path("value").asText());
        assertTrue(delivered.path("headers").get(0).path("traceId").isArray());
        assertEquals(List.of(97, 98, 99, 49, 50, 51),
                MAPPER.convertValue(delivered.path("headers").get(0).path("traceId"), List.class));
        assertFalse(delivered.has("eventSourceKey"));
        assertTrue(delivered.path("value").isTextual());
    }

    @Test
    void pollKafka_doesNotCommitWhenDeliveryFails() throws Exception {
        Pipe pipe = selfManagedKafkaPipe();
        KafkaRecordDto record = kafkaRecord("orders", 0, 7L, null,
                "{\"status\":\"active\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        when(kafkaConsumerManager.poll(pipe)).thenReturn(List.of(record));
        when(kafkaConsumerManager.resolveBootstrapServers(pipe)).thenReturn("broker-1:9092");
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(targetInvoker).invoke(eq(pipe), anyString(), eq("us-east-1"));

        poller.pollKafka(pipe, "us-east-1");

        verify(kafkaConsumerManager, never()).commit(eq(pipe), anyList());
    }

    @Test
    void pollKafka_commitsDeliveredPrefixWhenLaterRecordFails() throws Exception {
        Pipe pipe = selfManagedKafkaPipe();
        KafkaRecordDto first = kafkaRecord("orders", 0, 0L, null,
                "{\"status\":\"active\",\"id\":\"order-1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        KafkaRecordDto second = kafkaRecord("orders", 0, 1L, null,
                "{\"status\":\"active\",\"id\":\"order-2\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        when(kafkaConsumerManager.poll(pipe)).thenReturn(List.of(first, second));
        when(kafkaConsumerManager.resolveBootstrapServers(pipe)).thenReturn("broker-1:9092");
        org.mockito.Mockito.doNothing()
                .doThrow(new RuntimeException("boom"))
                .when(targetInvoker).invoke(eq(pipe), anyString(), eq("us-east-1"));

        poller.pollKafka(pipe, "us-east-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KafkaOffsetDto>> offsetsCaptor = ArgumentCaptor.forClass(List.class);
        verify(kafkaConsumerManager).commit(eq(pipe), offsetsCaptor.capture());
        assertEquals(1L, singleOffset(offsetsCaptor.getValue(), "orders", 0));
    }

    @Test
    void pollKafka_laterGeneratedBatchDoesNotCommitPastAnEarlierFailureInTheSamePartition() throws Exception {
        // A REST Proxy poll can return more than one BatchSize worth of records, so pollKafka
        // splits them into several generated batches. If the first batch fails to deliver record 0
        // and a later batch then succeeds delivering record 1 from the same partition, the second
        // batch's commit must not advance past record 0, or it is lost forever.
        Pipe pipe = selfManagedKafkaPipe();
        when(kafkaConsumerManager.resolveBatchSize(eq(pipe), anyInt())).thenReturn(1);
        KafkaRecordDto first = kafkaRecord("orders", 0, 0L, null,
                "{\"status\":\"active\",\"id\":\"order-1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        KafkaRecordDto second = kafkaRecord("orders", 0, 1L, null,
                "{\"status\":\"active\",\"id\":\"order-2\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        when(kafkaConsumerManager.poll(pipe)).thenReturn(List.of(first, second));
        when(kafkaConsumerManager.resolveBootstrapServers(pipe)).thenReturn("broker-1:9092");
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .doNothing()
                .when(targetInvoker).invoke(eq(pipe), anyString(), eq("us-east-1"));

        poller.pollKafka(pipe, "us-east-1");

        verify(kafkaConsumerManager, never()).commit(eq(pipe), anyList());
    }

    @Test
    void pollKafka_representsNullKeyAndValueAsJsonNull() throws Exception {
        Pipe pipe = nullableSelfManagedKafkaPipe();
        KafkaRecordDto record = kafkaRecord("orders", 0, 3L, null, null);

        when(kafkaConsumerManager.poll(pipe)).thenReturn(List.of(record));
        when(kafkaConsumerManager.resolveBootstrapServers(pipe)).thenReturn("broker-1:9092");

        poller.pollKafka(pipe, "us-east-1");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(targetInvoker).invoke(eq(pipe), payloadCaptor.capture(), eq("us-east-1"));
        JsonNode delivered = MAPPER.readTree(payloadCaptor.getValue());
        assertTrue(delivered.path("key").isNull());
        assertTrue(delivered.path("value").isNull());
    }

    @Test
    void pollKafka_lambdaCommitsSuccessfulPrefixBeforeLaterFailure() throws Exception {
        Pipe pipe = lambdaSelfManagedKafkaPipe();
        KafkaRecordDto first = kafkaRecord("orders", 0, 0L, null,
                "{\"status\":\"active\",\"id\":\"order-1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        KafkaRecordDto skipped = kafkaRecord("orders", 0, 1L, null,
                "{\"status\":\"inactive\",\"id\":\"order-2\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        KafkaRecordDto failing = kafkaRecord("orders", 0, 2L, null,
                "{\"status\":\"active\",\"id\":\"order-3\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        when(kafkaConsumerManager.poll(pipe)).thenReturn(List.of(first, skipped, failing));
        when(kafkaConsumerManager.resolveBootstrapServers(pipe)).thenReturn("broker-1:9092");
        org.mockito.Mockito.doNothing()
                .doThrow(new RuntimeException("boom"))
                .when(targetInvoker).invoke(eq(pipe), anyString(), eq("us-east-1"));

        poller.pollKafka(pipe, "us-east-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KafkaOffsetDto>> offsetsCaptor = ArgumentCaptor.forClass(List.class);
        verify(kafkaConsumerManager).commit(eq(pipe), offsetsCaptor.capture());
        assertEquals(2L, singleOffset(offsetsCaptor.getValue(), "orders", 0));
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

    private Pipe nullableSelfManagedKafkaPipe() throws Exception {
        Pipe pipe = new Pipe();
        pipe.setName("nullable-orders-pipe");
        pipe.setArn("arn:aws:pipes:us-east-1:000000000000:pipe/nullable-orders-pipe");
        pipe.setSource("smk://broker-1:9092");
        pipe.setTarget("arn:aws:sqs:us-east-1:000000000000:orders-target");
        pipe.setSourceParameters(MAPPER.readTree("""
                {
                  "SelfManagedKafkaParameters": {
                    "TopicName": "orders"
                  },
                  "FilterCriteria": {
                    "Filters": [
                      {"Pattern": "{\\\"key\\\": [{\\\"exists\\\": false}]}"}
                    ]
                  }
                }
                """));
        return pipe;
    }

    private Pipe lambdaSelfManagedKafkaPipe() throws Exception {
        Pipe pipe = selfManagedKafkaPipe();
        pipe.setTarget("arn:aws:lambda:us-east-1:000000000000:function:orders-target");
        return pipe;
    }

    private static KafkaRecordDto kafkaRecord(String topic, int partition, long offset, byte[] key, byte[] value,
                                              KafkaHeaderDto... headers) {
        return new KafkaRecordDto(topic, partition, offset, System.currentTimeMillis(),
                KafkaRecordDto.DEFAULT_TIMESTAMP_TYPE, key, value, List.of(headers));
    }

    private static long singleOffset(List<KafkaOffsetDto> offsets, String topic, int partition) {
        return offsets.stream()
                .filter(offset -> offset.topic().equals(topic) && offset.partition() == partition)
                .mapToLong(KafkaOffsetDto::offset)
                .findFirst()
                .orElseThrow();
    }
}
