package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CloudWatchLogsSubscriptionDeliveryServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "123456789012";
    private static final String GROUP = "/app/production";
    private static final String STREAM = "backend-stream";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CloudWatchLogsService logsService;
    private CloudWatchLogsSubscriptionDeliveryService deliveryService;
    private LambdaService lambdaService;
    private KinesisService kinesisService;
    private FirehoseService firehoseService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        Event<LogEventsIngested> ingestedEvent = mock(Event.class);

        logsService = new CloudWatchLogsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                10_000,
                Integer.MAX_VALUE,
                regionResolver,
                0L,
                System::currentTimeMillis,
                ingestedEvent,
                null
        );

        lambdaService = mock(LambdaService.class);
        kinesisService = mock(KinesisService.class);
        firehoseService = mock(FirehoseService.class);

        deliveryService = new CloudWatchLogsSubscriptionDeliveryService(
                logsService,
                lambdaService,
                kinesisService,
                firehoseService,
                regionResolver,
                MAPPER
        );

        doAnswer(invocation -> {
            LogEventsIngested event = invocation.getArgument(0);
            deliveryService.onLogEventsIngested(event);
            return null;
        }).when(ingestedEvent).fire(any());

        logsService.createLogGroup(GROUP, null, null, REGION);
        logsService.createLogStream(GROUP, STREAM, REGION);
    }

    private static byte[] decompress(byte[] compressed) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            in.transferTo(out);
        }
        return out.toByteArray();
    }

    @Test
    void deliversMatchingEventToLambda() throws Exception {
        String lambdaArn = "arn:aws:lambda:us-east-1:123456789012:function:log-processor";
        logsService.putSubscriptionFilter(GROUP, "lambda-filter", "ERROR", lambdaArn, null, REGION);

        long timestamp = 1759406400000L;
        logsService.putLogEvents(GROUP, STREAM, List.of(
                Map.of("timestamp", timestamp, "message", "ERROR: database connection timed out"),
                Map.of("timestamp", timestamp + 10, "message", "INFO: retry successful")
        ), REGION);

        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invokeArn(eq(lambdaArn), captor.capture(), eq(InvocationType.Event));

        JsonNode lambdaRoot = MAPPER.readTree(captor.getValue());
        assertTrue(lambdaRoot.has("awslogs"));
        String base64Data = lambdaRoot.path("awslogs").path("data").asText();
        assertNotNull(base64Data);

        byte[] decompressedBytes = decompress(Base64.getDecoder().decode(base64Data));
        JsonNode payload = MAPPER.readTree(decompressedBytes);

        assertEquals("DATA_MESSAGE", payload.get("messageType").asText());
        assertEquals(ACCOUNT, payload.get("owner").asText());
        assertEquals(GROUP, payload.get("logGroup").asText());
        assertEquals(STREAM, payload.get("logStream").asText());
        assertEquals(1, payload.get("subscriptionFilters").size());
        assertEquals("lambda-filter", payload.get("subscriptionFilters").get(0).asText());

        JsonNode logEvents = payload.get("logEvents");
        assertEquals(1, logEvents.size());
        assertEquals("ERROR: database connection timed out", logEvents.get(0).get("message").asText());
        assertEquals(timestamp, logEvents.get(0).get("timestamp").asLong());
        assertNotNull(logEvents.get(0).get("id").asText());
    }

    @Test
    void deliversMatchingEventToKinesisWithByLogStreamDistribution() throws Exception {
        String kinesisArn = "arn:aws:kinesis:us-east-1:123456789012:stream/order-events";
        logsService.putSubscriptionFilter(GROUP, "kinesis-filter", "", kinesisArn, "ByLogStream", REGION);

        long timestamp = 1759406401000L;
        logsService.putLogEvents(GROUP, STREAM, List.of(
                Map.of("timestamp", timestamp, "message", "user logged in")
        ), REGION);

        ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> partitionKeyCaptor = ArgumentCaptor.forClass(String.class);

        verify(kinesisService).putRecordForAccount(
                eq(ACCOUNT),
                eq("order-events"),
                dataCaptor.capture(),
                partitionKeyCaptor.capture(),
                eq(REGION)
        );

        assertEquals(STREAM, partitionKeyCaptor.getValue());

        byte[] decompressedBytes = decompress(dataCaptor.getValue());
        JsonNode payload = MAPPER.readTree(decompressedBytes);

        assertEquals("DATA_MESSAGE", payload.get("messageType").asText());
        assertEquals(ACCOUNT, payload.get("owner").asText());
        assertEquals(GROUP, payload.get("logGroup").asText());
        assertEquals(STREAM, payload.get("logStream").asText());
        assertEquals("kinesis-filter", payload.get("subscriptionFilters").get(0).asText());
        assertEquals(1, payload.get("logEvents").size());
        assertEquals("user logged in", payload.get("logEvents").get(0).get("message").asText());
    }

    @Test
    void deliversMatchingEventToKinesisWithRandomDistribution() throws Exception {
        String kinesisArn = "arn:aws:kinesis:us-east-1:123456789012:stream/order-events";
        logsService.putSubscriptionFilter(GROUP, "kinesis-filter", "", kinesisArn, "Random", REGION);

        long timestamp = 1759406402000L;
        logsService.putLogEvents(GROUP, STREAM, List.of(
                Map.of("timestamp", timestamp, "message", "payment processed")
        ), REGION);

        ArgumentCaptor<String> partitionKeyCaptor = ArgumentCaptor.forClass(String.class);

        verify(kinesisService).putRecordForAccount(
                eq(ACCOUNT),
                eq("order-events"),
                any(byte[].class),
                partitionKeyCaptor.capture(),
                eq(REGION)
        );

        String partitionKey = partitionKeyCaptor.getValue();
        assertFalse(partitionKey.equals(STREAM));
        assertNotNull(UUID.fromString(partitionKey));
    }

    @Test
    void deliversMatchingEventToFirehose() throws Exception {
        String firehoseArn = "arn:aws:firehose:us-east-1:123456789012:deliverystream/app-logs-sink";
        logsService.putSubscriptionFilter(GROUP, "firehose-filter", "CRITICAL", firehoseArn, null, REGION);

        long timestamp = 1759406403000L;
        logsService.putLogEvents(GROUP, STREAM, List.of(
                Map.of("timestamp", timestamp, "message", "CRITICAL: out of memory")
        ), REGION);

        ArgumentCaptor<Record> recordCaptor = ArgumentCaptor.forClass(Record.class);

        verify(firehoseService).putRecord(
                eq(ACCOUNT),
                eq(REGION),
                eq("app-logs-sink"),
                recordCaptor.capture()
        );

        byte[] decompressedBytes = decompress(recordCaptor.getValue().getData());
        JsonNode payload = MAPPER.readTree(decompressedBytes);

        assertEquals("DATA_MESSAGE", payload.get("messageType").asText());
        assertEquals(ACCOUNT, payload.get("owner").asText());
        assertEquals(GROUP, payload.get("logGroup").asText());
        assertEquals(STREAM, payload.get("logStream").asText());
        assertEquals("firehose-filter", payload.get("subscriptionFilters").get(0).asText());
        assertEquals(1, payload.get("logEvents").size());
        assertEquals("CRITICAL: out of memory", payload.get("logEvents").get(0).get("message").asText());
    }

    @Test
    void nonMatchingEventDeliversNothing() {
        String lambdaArn = "arn:aws:lambda:us-east-1:123456789012:function:log-processor";
        logsService.putSubscriptionFilter(GROUP, "error-filter", "ERROR", lambdaArn, null, REGION);

        logsService.putLogEvents(GROUP, STREAM, List.of(
                Map.of("timestamp", System.currentTimeMillis(), "message", "INFO: everything normal")
        ), REGION);

        verifyNoInteractions(lambdaService);
        verifyNoInteractions(kinesisService);
        verifyNoInteractions(firehoseService);
    }

    @Test
    void filterPatternSelectingPartOfBatchDeliversOnlyMatches() throws Exception {
        String lambdaArn = "arn:aws:lambda:us-east-1:123456789012:function:log-processor";
        logsService.putSubscriptionFilter(GROUP, "warn-filter", "WARN", lambdaArn, null, REGION);

        logsService.putLogEvents(GROUP, STREAM, List.of(
                Map.of("timestamp", 1000L, "message", "WARN: disk usage high"),
                Map.of("timestamp", 1001L, "message", "INFO: status check"),
                Map.of("timestamp", 1002L, "message", "WARN: memory usage high")
        ), REGION);

        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invokeArn(eq(lambdaArn), captor.capture(), eq(InvocationType.Event));

        JsonNode root = MAPPER.readTree(captor.getValue());
        byte[] decompressed = decompress(Base64.getDecoder().decode(root.path("awslogs").path("data").asText()));
        JsonNode payload = MAPPER.readTree(decompressed);

        JsonNode events = payload.get("logEvents");
        assertEquals(2, events.size());
        assertEquals("WARN: disk usage high", events.get(0).get("message").asText());
        assertEquals("WARN: memory usage high", events.get(1).get("message").asText());
    }

    @Test
    void twoFiltersOnOneLogGroupBothFire() {
        String lambdaArn = "arn:aws:lambda:us-east-1:123456789012:function:error-handler";
        String kinesisArn = "arn:aws:kinesis:us-east-1:123456789012:stream/all-events";

        logsService.putSubscriptionFilter(GROUP, "filter-lambda", "ERROR", lambdaArn, null, REGION);
        logsService.putSubscriptionFilter(GROUP, "filter-kinesis", "", kinesisArn, null, REGION);

        logsService.putLogEvents(GROUP, STREAM, List.of(
                Map.of("timestamp", System.currentTimeMillis(), "message", "ERROR: fatal crash")
        ), REGION);

        verify(lambdaService).invokeArn(eq(lambdaArn), any(byte[].class), eq(InvocationType.Event));
        verify(kinesisService).putRecordForAccount(eq(ACCOUNT), eq("all-events"), any(byte[].class), any(), eq(REGION));
    }

    @Test
    void unresolvableDestinationLeavesPutLogEventsSuccessful() {
        String brokenArn = "arn:aws:kinesis:us-east-1:123456789012:stream/nonexistent";
        logsService.putSubscriptionFilter(GROUP, "broken-filter", "", brokenArn, null, REGION);

        doThrow(new AwsException("ResourceNotFoundException", "Stream not found", 400))
                .when(kinesisService).putRecordForAccount(any(), any(), any(), any(), any());

        String nextToken = logsService.putLogEvents(GROUP, STREAM, List.of(
                Map.of("timestamp", 1000L, "message", "persisted message")
        ), REGION);

        assertNotNull(nextToken);

        CloudWatchLogsService.LogEventsResult result =
                logsService.getLogEvents(GROUP, STREAM, null, null, 10, false, null, REGION);
        assertEquals(1, result.events().size());
        assertEquals("persisted message", result.events().getFirst().getMessage());
    }

    @Test
    void unparseableDestinationArnLeavesPutLogEventsSuccessful() {
        logsService.putSubscriptionFilter(GROUP, "invalid-arn-filter", "", "not-an-arn", null, REGION);

        String nextToken = logsService.putLogEvents(GROUP, STREAM, List.of(
                Map.of("timestamp", 2000L, "message", "still stored")
        ), REGION);

        assertNotNull(nextToken);
        verifyNoInteractions(lambdaService);
        verifyNoInteractions(kinesisService);
        verifyNoInteractions(firehoseService);

        CloudWatchLogsService.LogEventsResult result =
                logsService.getLogEvents(GROUP, STREAM, null, null, 10, false, null, REGION);
        assertEquals(1, result.events().size());
        assertEquals("still stored", result.events().getFirst().getMessage());
    }

    @Test
    void logGroupWithNoFiltersBehavesNormally() {
        String otherGroup = "/app/unsubscribed";
        logsService.createLogGroup(otherGroup, null, null, REGION);
        logsService.createLogStream(otherGroup, "stream1", REGION);

        String nextToken = logsService.putLogEvents(otherGroup, "stream1", List.of(
                Map.of("timestamp", 3000L, "message", "unsubscribed event")
        ), REGION);

        assertNotNull(nextToken);
        verifyNoInteractions(lambdaService);
        verifyNoInteractions(kinesisService);
        verifyNoInteractions(firehoseService);

        CloudWatchLogsService.LogEventsResult result =
                logsService.getLogEvents(otherGroup, "stream1", null, null, 10, false, null, REGION);
        assertEquals(1, result.events().size());
        assertEquals("unsubscribed event", result.events().getFirst().getMessage());
    }

    @Test
    void crossAccountDestinationWithoutLogsDestinationIsSkipped() {
        String otherAccountArn = "arn:aws:kinesis:us-east-1:999999999999:stream/other-stream";
        logsService.putSubscriptionFilter(GROUP, "cross-acct-filter", "", otherAccountArn, null, REGION);

        String nextToken = logsService.putLogEvents(GROUP, STREAM, List.of(
                Map.of("timestamp", 4000L, "message", "message for foreign stream")
        ), REGION);

        assertNotNull(nextToken);
        verifyNoInteractions(kinesisService);
    }

    @Test
    void selfSubscribedLambdaSkipsDeliveryToPreventRecursiveLoop() {
        String fnName = "my-worker";
        String selfGroup = "/aws/lambda/" + fnName;
        String selfArn = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:" + fnName;

        logsService.createLogGroup(selfGroup, null, null, REGION);
        logsService.createLogStream(selfGroup, "stream1", REGION);
        logsService.putSubscriptionFilter(selfGroup, "loop-filter", "", selfArn, null, REGION);

        String nextToken = logsService.putLogEvents(selfGroup, "stream1", List.of(
                Map.of("timestamp", 5000L, "message", "lambda container log line")
        ), REGION);

        assertNotNull(nextToken);
        verifyNoInteractions(lambdaService);
    }

    @Test
    void largeBatchExceedingPayloadLimitIsChunkedAcrossMultipleRecords() {
        String kinesisArn = "arn:aws:kinesis:us-east-1:" + ACCOUNT + ":stream/large-stream";
        logsService.putSubscriptionFilter(GROUP, "chunk-filter", "", kinesisArn, null, REGION);

        // Generate events with random content so gzip payload exceeds 1,000,000 bytes
        Random rnd = new Random(42);
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            byte[] bytes = new byte[40_000];
            rnd.nextBytes(bytes);
            String message = Base64.getEncoder().encodeToString(bytes);
            events.add(Map.of("timestamp", 6000L + i, "message", message));
        }

        logsService.putLogEvents(GROUP, STREAM, events, REGION);

        ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(kinesisService, atLeast(2)).putRecordForAccount(
                eq(ACCOUNT),
                eq("large-stream"),
                dataCaptor.capture(),
                any(),
                eq(REGION)
        );

        for (byte[] recordBytes : dataCaptor.getAllValues()) {
            assertTrue(recordBytes.length <= 1_000_000, "Delivered record exceeds maximum payload bytes");
        }
    }

    @Test
    void failedChunkDoesNotStopDeliveryOfRemainingChunks() {
        String kinesisArn = "arn:aws:kinesis:us-east-1:" + ACCOUNT + ":stream/isolated-stream";
        logsService.putSubscriptionFilter(GROUP, "resilient-filter", "", kinesisArn, null, REGION);

        Random rnd = new Random(42);
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            byte[] bytes = new byte[40_000];
            rnd.nextBytes(bytes);
            String message = Base64.getEncoder().encodeToString(bytes);
            events.add(Map.of("timestamp", 7000L + i, "message", message));
        }

        doThrow(new AwsException("ProvisionedThroughputExceededException", "Rate exceeded", 400))
                .doReturn("seq-ok")
                .when(kinesisService).putRecordForAccount(any(), any(), any(), any(), any());

        String nextToken = logsService.putLogEvents(GROUP, STREAM, events, REGION);
        assertNotNull(nextToken);

        verify(kinesisService, atLeast(2)).putRecordForAccount(
                eq(ACCOUNT),
                eq("isolated-stream"),
                any(byte[].class),
                any(),
                eq(REGION)
        );
    }
}
