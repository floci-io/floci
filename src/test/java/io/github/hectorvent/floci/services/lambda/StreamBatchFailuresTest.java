package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.testutil.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StreamBatchFailuresTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STREAM_ARN = "arn:aws:dynamodb:us-east-1:000000000000:table/t/stream/2026-08-01T00:00:00.000";
    private static final String EVENT = "{\"Records\":[{\"eventSource\":\"aws:dynamodb\"}]}";
    /** Whole seconds: {@code ISO_INSTANT} would drop the fraction that AWS always writes. */
    private static final StreamBatchFailures.DiscardedBatch BATCH = batch(() -> EVENT);

    private SqsService sqsService;
    private SnsService snsService;
    private S3Service s3Service;
    private StreamBatchFailures failures;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        sqsService = mock(SqsService.class);
        snsService = mock(SnsService.class);
        s3Service = mock(S3Service.class);
        failures = new StreamBatchFailures(sqsService, snsService, s3Service, MAPPER, config);
    }

    @Test
    void s3KeyUsesTheUtcLayout() {
        UUID randomId = UUID.fromString("12345678-1234-1234-1234-123456789abc");

        String key = StreamBatchFailures.s3Key("esm-1", "shard-1", Instant.parse("2026-09-14T23:07:08Z"), randomId);

        assertEquals("aws/lambda/esm-1/shard-1/2026/09/14/"
                + "2026-09-14T23.07.08-12345678-1234-1234-1234-123456789abc", key);
    }

    @Test
    void retryBackoffDoublesFromThePollIntervalAndCapsAtOneMinute() {
        assertEquals(1_000, StreamBatchFailures.retryBackoffMs(500, 1));
        assertEquals(2_000, StreamBatchFailures.retryBackoffMs(500, 2));
        assertEquals(32_000, StreamBatchFailures.retryBackoffMs(500, 6));
        assertEquals(60_000, StreamBatchFailures.retryBackoffMs(500, 7));
        assertEquals(60_000, StreamBatchFailures.retryBackoffMs(500, 10_000), "a large retry count must not overflow");
    }

    @Test
    void retryAttemptsRunOutAfterOnePlusTheMaximumInvocations() {
        assertFalse(StreamBatchFailures.retryAttemptsExhausted(esm(null, null), 10_001), "unset means no limit");
        assertFalse(StreamBatchFailures.retryAttemptsExhausted(esm(-1, null), 10_001), "-1 means no limit");
        assertTrue(StreamBatchFailures.retryAttemptsExhausted(esm(0, null), 1));
        assertFalse(StreamBatchFailures.retryAttemptsExhausted(esm(10, null), 10));
        assertTrue(StreamBatchFailures.retryAttemptsExhausted(esm(10, null), 11));
    }

    @Test
    void maximumRecordAgeIsExceededOnlyForAFiniteLimit() {
        Instant arrival = Instant.parse("2026-10-20T12:07:26.012Z");
        long weekLater = arrival.plusSeconds(604_801).toEpochMilli();
        long almostAMinute = arrival.toEpochMilli() + 59_999;
        long aMinute = arrival.toEpochMilli() + 60_000;

        assertFalse(StreamBatchFailures.maximumRecordAgeExceeded(esm(null, null), arrival, weekLater), "unset");
        assertFalse(StreamBatchFailures.maximumRecordAgeExceeded(esm(null, -1), arrival, weekLater), "-1");
        assertFalse(StreamBatchFailures.maximumRecordAgeExceeded(esm(null, 60), arrival, almostAMinute));
        assertTrue(StreamBatchFailures.maximumRecordAgeExceeded(esm(null, 60), arrival, aMinute));
    }

    @Test
    void onlyAFunctionErrorOnMoreThanOneRecordSplitsAndOnlyWhenBisecting() {
        EventSourceMapping bisecting = esm(null, null);
        bisecting.setBisectBatchOnFunctionError(true);
        InvokeResult functionError = new InvokeResult(200, "Unhandled", new byte[0], null, "req-1");

        assertTrue(StreamBatchFailures.splitsOnFunctionError(bisecting, functionError, 2));
        assertFalse(StreamBatchFailures.splitsOnFunctionError(bisecting, functionError, 1), "a single record");
        assertFalse(StreamBatchFailures.splitsOnFunctionError(bisecting, new InvokeResult(), 2), "batchItemFailures");
        assertFalse(StreamBatchFailures.splitsOnFunctionError(esm(null, null), functionError, 2), "unset");
    }

    @Test
    void aSplitBatchIsRefetchedAtItsFirstHalfRoundedUp() {
        assertEquals(2, StreamBatchFailures.splitBatchSize(4));
        assertEquals(3, StreamBatchFailures.splitBatchSize(5));
        assertEquals(1, StreamBatchFailures.splitBatchSize(2));
    }

    @Test
    void theSplitSizeLimitsTheFetchOnlyWhileBisecting() {
        Map<String, Integer> splitSizes = Map.of("esm-1:shard:42", 3);
        EventSourceMapping bisecting = esm(null, null);
        bisecting.setBisectBatchOnFunctionError(true);

        assertEquals(3, StreamBatchFailures.bisectLimit(bisecting, splitSizes, "esm-1:shard:42"));
        assertEquals(Integer.MAX_VALUE, StreamBatchFailures.bisectLimit(bisecting, splitSizes, "esm-1:shard:43"));
        assertEquals(Integer.MAX_VALUE, StreamBatchFailures.bisectLimit(esm(null, null), splitSizes, "esm-1:shard:42"));
    }

    @Test
    void sqsRecordFillsDefaultsWritesTheTimestampInMillisecondsAndDoesNotBuildTheEvent() throws Exception {
        EventSourceMapping esm = esm(0, null);
        esm.setDestinationConfig(destination("arn:aws:sqs:us-east-1:000000000000:dlq"));
        StreamBatchFailures.DiscardedBatch batch = batch(() -> {
            throw new AssertionError("only an S3 destination may build the event");
        });

        failures.sendToOnFailureDestination(esm, batch, new InvokeResult(), 1,
                StreamBatchFailures.RETRY_ATTEMPTS_EXHAUSTED);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sqsService, times(1)).sendMessage(eq("http://localhost:4566/000000000000/dlq"), body.capture(),
                eq(0), eq("us-east-1"));
        JsonNode record = MAPPER.readTree(body.getValue());
        assertEquals("", record.at("/requestContext/requestId").asText(), record.toString());
        assertEquals("", record.at("/requestContext/functionArn").asText());
        assertEquals(List.of("statusCode", "executedVersion"), fieldNames(record.path("responseContext")),
                "no functionError without a function error: " + record);
        assertEquals(200, record.at("/responseContext/statusCode").asInt());
        assertEquals("$LATEST", record.at("/responseContext/executedVersion").asText());
        assertEquals("2023-11-14T22:13:20Z", record.at("/DDBStreamBatchInfo/approximateArrivalOfFirstRecord").asText(),
                "the arrival times are written as the source gives them");
        assertEquals("2023-11-14T22:13:21Z", record.at("/DDBStreamBatchInfo/approximateArrivalOfLastRecord").asText());
        assertTrue(record.path("timestamp").asText().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"),
                "timestamp must be ISO-8601 with exactly three fraction digits: " + record.path("timestamp"));
    }

    @Test
    void destinationInAnotherRegionIsReachedInItsOwnRegion() {
        EventSourceMapping esm = esm(0, null);
        esm.setDestinationConfig(destination("arn:aws:sqs:eu-west-2:000000000000:dlq"));

        failures.sendToOnFailureDestination(esm, BATCH, new InvokeResult(), 1,
                StreamBatchFailures.RETRY_ATTEMPTS_EXHAUSTED);

        verify(sqsService, times(1)).sendMessage(eq("http://localhost:4566/000000000000/dlq"), anyString(),
                eq(0), eq("eu-west-2"));
    }

    @Test
    void s3RecordCarriesTheEventAsAPayloadString() throws Exception {
        EventSourceMapping esm = esm(0, null);
        esm.setDestinationConfig(destination("arn:aws:s3:::failures"));
        InvokeResult error = new InvokeResult(200, "Unhandled", new byte[0], null, "req-1");

        failures.sendToOnFailureDestination(esm, BATCH, error, 1, StreamBatchFailures.MAXIMUM_RECORD_AGE_EXCEEDED);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service, times(1)).putObject(eq("failures"), key.capture(), body.capture(),
                eq("application/json"), eq(Map.of()));
        assertTrue(key.getValue().startsWith("aws/lambda/esm-1/shardId-000000000000/"), key.getValue());
        JsonNode record = MAPPER.readTree(new String(body.getValue(), StandardCharsets.UTF_8));
        assertEquals(List.of("requestContext", "responseContext", "version", "payload", "timestamp", "DDBStreamBatchInfo"),
                fieldNames(record));
        assertEquals(EVENT, record.path("payload").asText());
        assertEquals("MaximumRecordAgeExceeded", record.at("/requestContext/condition").asText());
        assertEquals("Unhandled", record.at("/responseContext/functionError").asText());
        verifyNoInteractions(sqsService, snsService);
    }

    @Test
    void refusedSendIsLoggedWithItsCauseNotThrown() {
        EventSourceMapping esm = esm(0, null);
        esm.setDestinationConfig(destination("arn:aws:sqs:us-east-1:000000000000:gone"));
        AwsException refusal = new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                "The specified queue does not exist.", 400);
        when(sqsService.sendMessage(anyString(), anyString(), anyInt(), anyString())).thenThrow(refusal);

        List<LogRecord> records = LogCapture.capture(StreamBatchFailures.class, () ->
                failures.sendToOnFailureDestination(esm, BATCH, new InvokeResult(), 1,
                        StreamBatchFailures.RETRY_ATTEMPTS_EXHAUSTED));

        LogRecord error = records.stream()
                .filter(record -> record.getLevel().intValue() >= Level.SEVERE.intValue())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no error was logged: " + records));
        assertSame(refusal, error.getThrown(), "the error log must keep the cause and its stack trace");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "arn:aws:lambda:us-east-1:000000000000:function:other", "arn:aws:s3:::"})
    void unusableDestinationSendsNothing(String destinationArn) {
        EventSourceMapping esm = esm(0, null);
        esm.setDestinationConfig(destination(destinationArn));

        failures.sendToOnFailureDestination(esm, BATCH, new InvokeResult(), 1,
                StreamBatchFailures.RETRY_ATTEMPTS_EXHAUSTED);

        verifyNoInteractions(sqsService, snsService, s3Service);
    }

    @Test
    void mappingWithoutDestinationSendsNothing() {
        failures.sendToOnFailureDestination(esm(0, null), BATCH, new InvokeResult(), 1,
                StreamBatchFailures.RETRY_ATTEMPTS_EXHAUSTED);

        verifyNoInteractions(sqsService, snsService, s3Service);
    }

    private static StreamBatchFailures.DiscardedBatch batch(Supplier<String> event) {
        return new StreamBatchFailures.DiscardedBatch("DDBStreamBatchInfo", "shardId-000000000000", "s1", "s2",
                "2023-11-14T22:13:20Z", "2023-11-14T22:13:21Z", 2, STREAM_ARN, event);
    }

    private static EventSourceMapping esm(Integer maximumRetryAttempts, Integer maximumRecordAgeInSeconds) {
        EventSourceMapping esm = new EventSourceMapping();
        esm.setUuid("esm-1");
        esm.setAccountId("000000000000");
        esm.setRegion("us-east-1");
        esm.setEventSourceArn(STREAM_ARN);
        esm.setMaximumRetryAttempts(maximumRetryAttempts);
        esm.setMaximumRecordAgeInSeconds(maximumRecordAgeInSeconds);
        return esm;
    }

    private static EventSourceMapping.DestinationConfig destination(String arn) {
        EventSourceMapping.OnFailure onFailure = new EventSourceMapping.OnFailure();
        onFailure.setDestination(arn);
        EventSourceMapping.DestinationConfig destinationConfig = new EventSourceMapping.DestinationConfig();
        destinationConfig.setOnFailure(onFailure);
        return destinationConfig;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
