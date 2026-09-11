package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.ProcessingConfiguration;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.Processor;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.ProcessorParameter;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The result semantics, error codes and error-object shape asserted here were
 * probed against real AWS (us-west-2, 2026-09-10), including which failures are
 * retried: a function that errors is, a response the function shaped wrongly is
 * not.
 */
class FirehoseLambdaTransformerTest {

    private static final String BUCKET = "results";
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:transform";
    private static final Instant DELIVERY_TIME = Instant.parse("2026-01-01T00:00:00Z");

    private LambdaService lambdaService;
    private S3Service s3Service;
    private FirehoseLambdaTransformer transformer;
    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode invocationEvent;

    @BeforeEach
    void setUp() {
        lambdaService = mock(LambdaService.class);
        s3Service = mock(S3Service.class);
        transformer = new FirehoseLambdaTransformer(lambdaService, s3Service, mapper,
                new RegionResolver("us-east-1", "000000000000"));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static DeliveryStreamDescription stream(Processor... processors) {
        ProcessingConfiguration processing = new ProcessingConfiguration();
        processing.setEnabled(true);
        processing.setProcessors(List.of(processors));
        S3Destination s3 = new S3Destination();
        s3.setBucketArn("arn:aws:s3:::" + BUCKET);
        s3.setErrorOutputPrefix("errors/!{firehose:error-output-type}/");
        s3.setProcessingConfiguration(processing);
        return new DeliveryStreamDescription("stream", "arn:aws:firehose:us-west-2:000000000000:deliverystream/stream", s3);
    }

    private static Processor lambdaProcessor(String... parameters) {
        List<ProcessorParameter> list = new ArrayList<>();
        list.add(parameter("LambdaArn", FUNCTION_ARN));
        for (int i = 0; i < parameters.length; i += 2) {
            list.add(parameter(parameters[i], parameters[i + 1]));
        }
        Processor processor = new Processor();
        processor.setType("Lambda");
        processor.setParameters(list);
        return processor;
    }

    private static ProcessorParameter parameter(String name, String value) {
        ProcessorParameter parameter = new ProcessorParameter();
        parameter.setParameterName(name);
        parameter.setParameterValue(value);
        return parameter;
    }

    private static byte[] record(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static String encode(String body) {
        return Base64.getEncoder().encodeToString(record(body));
    }

    /**
     * Answers the invocation from the record ids the transformer actually generated,
     * which is the only way a test can name them: they are minted per batch.
     */
    private void replyWith(Function<List<String>, String> responder) {
        when(lambdaService.invokeArn(anyString(), any(), any())).thenAnswer(invocation -> {
            invocationEvent = mapper.readTree((byte[]) invocation.getArgument(1));
            List<String> recordIds = new ArrayList<>();
            invocationEvent.get("records").forEach(node -> recordIds.add(node.get("recordId").asText()));
            InvokeResult result = new InvokeResult();
            result.setStatusCode(200);
            result.setPayload(responder.apply(recordIds).getBytes(StandardCharsets.UTF_8));
            return result;
        });
    }

    private static String records(String... entries) {
        return "{\"records\":[" + String.join(",", entries) + "]}";
    }

    private static String ok(String recordId, String data) {
        return "{\"recordId\":\"" + recordId + "\",\"result\":\"Ok\",\"data\":\"" + encode(data) + "\"}";
    }

    private static String outcome(String recordId, String result) {
        return "{\"recordId\":\"" + recordId + "\",\"result\":\"" + result + "\"}";
    }

    private List<String> delivered(FirehoseLambdaTransformer.Outcome outcome) {
        return outcome.records().stream()
                .map(data -> new String(data, StandardCharsets.UTF_8))
                .collect(Collectors.toList());
    }

    private List<JsonNode> errorLines() throws Exception {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service).putObject(eq(BUCKET), key.capture(), body.capture(),
                eq("application/octet-stream"), anyMap());
        assertTrue(key.getValue().startsWith("errors/processing-failed/"),
                "error output key was " + key.getValue());
        List<JsonNode> lines = new ArrayList<>();
        for (String line : new String(body.getValue(), StandardCharsets.UTF_8).split("\n")) {
            lines.add(mapper.readTree(line));
        }
        return lines;
    }

    private FirehoseLambdaTransformer.Outcome transform(DeliveryStreamDescription stream, byte[]... records) {
        return transformer.transform(stream, BUCKET, List.of(records), DELIVERY_TIME);
    }

    // ── pass-through ─────────────────────────────────────────────────────────

    @Test
    void aStreamWithoutAProcessingConfigurationIsNotTransformed() {
        S3Destination s3 = new S3Destination();
        s3.setBucketArn("arn:aws:s3:::" + BUCKET);
        DeliveryStreamDescription stream =
                new DeliveryStreamDescription("stream", "arn:aws:firehose:::stream", s3);
        List<byte[]> records = List.of(record("a"));

        FirehoseLambdaTransformer.Outcome outcome =
                transformer.transform(stream, BUCKET, records, DELIVERY_TIME);

        assertSame(records, outcome.records());
        verifyNoInteractions(lambdaService);
    }

    /**
     * Probed 2026-09-11: an omitted Enabled is stored as false, so it leaves the stream
     * untransformed. The conversion block treats an omitted Enabled the other way, which
     * is why this is asserted rather than inferred from it.
     */
    @Test
    void aProcessingConfigurationWithoutAnExplicitEnabledIsNotTransformed() {
        DeliveryStreamDescription stream = stream(lambdaProcessor());
        stream.s3Destination().getProcessingConfiguration().setEnabled(null);

        FirehoseLambdaTransformer.Outcome outcome = transform(stream, record("a"));

        assertEquals(List.of("a"), delivered(outcome));
        verifyNoInteractions(lambdaService);
    }

    @Test
    void aDisabledProcessingConfigurationIsNotTransformed() {
        DeliveryStreamDescription stream = stream(lambdaProcessor());
        stream.s3Destination().getProcessingConfiguration().setEnabled(false);

        transform(stream, record("a"));

        verifyNoInteractions(lambdaService);
    }

    @Test
    void aProcessorTypeFlociDoesNotApplyLeavesTheRecordsAlone() {
        Processor appendDelimiter = new Processor();
        appendDelimiter.setType("AppendDelimiterToRecord");

        FirehoseLambdaTransformer.Outcome outcome = transform(stream(appendDelimiter), record("a"));

        assertEquals(List.of("a"), delivered(outcome));
        verifyNoInteractions(lambdaService);
    }

    // ── invocation payload ───────────────────────────────────────────────────

    @Test
    void theInvocationCarriesTheProbedEventShape() {
        replyWith(ids -> records(ok(ids.get(0), "transformed")));

        transform(stream(lambdaProcessor()), record("original"));

        assertEquals(List.of("invocationId", "deliveryStreamArn", "region", "records"),
                fieldNames(invocationEvent));
        assertEquals("arn:aws:firehose:us-west-2:000000000000:deliverystream/stream",
                invocationEvent.get("deliveryStreamArn").asText());
        assertEquals("us-west-2", invocationEvent.get("region").asText());
        JsonNode record = invocationEvent.get("records").get(0);
        assertEquals(List.of("recordId", "approximateArrivalTimestamp", "data"), fieldNames(record));
        assertEquals(DELIVERY_TIME.toEpochMilli(), record.get("approximateArrivalTimestamp").asLong());
        assertEquals(encode("original"), record.get("data").asText());
        verify(lambdaService).invokeArn(eq(FUNCTION_ARN), any(), eq(InvocationType.RequestResponse));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // ── per-record results ───────────────────────────────────────────────────

    @Test
    void okReplacesTheRecordWithTheReturnedData() {
        replyWith(ids -> records(ok(ids.get(0), "one"), ok(ids.get(1), "two")));

        FirehoseLambdaTransformer.Outcome outcome =
                transform(stream(lambdaProcessor()), record("a"), record("b"));

        assertEquals(List.of("one", "two"), delivered(outcome));
        assertEquals(0, outcome.failedRecords());
        assertNull(outcome.errorKey());
        verify(s3Service, never()).putObject(anyString(), anyString(), any(), anyString(), anyMap());
    }

    @Test
    void droppedLeavesNoRecordAndNoErrorObject() {
        replyWith(ids -> records(outcome(ids.get(0), "Dropped"), ok(ids.get(1), "kept")));

        FirehoseLambdaTransformer.Outcome outcome =
                transform(stream(lambdaProcessor()), record("a"), record("b"));

        assertEquals(List.of("kept"), delivered(outcome));
        assertEquals(1, outcome.droppedRecords());
        assertEquals(0, outcome.failedRecords());
        assertNull(outcome.errorKey());
    }

    @Test
    void processingFailedRoutesOnlyThatRecordToTheErrorOutput() throws Exception {
        replyWith(ids -> records(outcome(ids.get(0), "ProcessingFailed"), ok(ids.get(1), "kept")));

        FirehoseLambdaTransformer.Outcome outcome =
                transform(stream(lambdaProcessor()), record("a"), record("b"));

        assertEquals(List.of("kept"), delivered(outcome));
        assertEquals(1, outcome.failedRecords());
        JsonNode line = errorLines().get(0);
        assertEquals("Lambda.ProcessingFailedStatus", line.get("errorCode").asText());
        assertEquals("ProcessingFailed status set for record", line.get("errorMessage").asText());
        assertEquals(encode("a"), line.get("rawData").asText());
    }

    @Test
    void aRecordTheFunctionDidNotReturnFails() throws Exception {
        replyWith(ids -> records(ok(ids.get(0), "one")));

        transform(stream(lambdaProcessor()), record("a"), record("b"));

        JsonNode line = errorLines().get(0);
        assertEquals("Lambda.MissingRecordId", line.get("errorCode").asText());
        assertEquals(encode("b"), line.get("rawData").asText());
    }

    @Test
    void aRecordIdReturnedTwiceFails() throws Exception {
        replyWith(ids -> records(ok(ids.get(0), "one"), ok(ids.get(0), "again"), ok(ids.get(1), "two")));

        FirehoseLambdaTransformer.Outcome outcome =
                transform(stream(lambdaProcessor()), record("a"), record("b"));

        assertEquals(List.of("two"), delivered(outcome));
        assertEquals("Lambda.DuplicatedRecordId", errorLines().get(0).get("errorCode").asText());
    }

    @Test
    void aRecordIdTheBatchNeverCarriedIsIgnored() {
        replyWith(ids -> records(ok(ids.get(0), "one"), ok("not-in-this-batch", "stray")));

        FirehoseLambdaTransformer.Outcome outcome = transform(stream(lambdaProcessor()), record("a"));

        assertEquals(List.of("one"), delivered(outcome));
        assertEquals(0, outcome.failedRecords());
    }

    @Test
    void okWithoutDataFails() throws Exception {
        replyWith(ids -> records(outcome(ids.get(0), "Ok")));

        transform(stream(lambdaProcessor()), record("a"));

        JsonNode line = errorLines().get(0);
        assertEquals("Lambda.InvalidReturnFormat", line.get("errorCode").asText());
        assertTrue(line.get("errorMessage").asText().startsWith("The data field cannot be null"));
    }

    /**
     * The message here is Floci's own: AWS was not probed for a data field that is not
     * base64 at all, only for one that is absent.
     */
    @Test
    void dataThatIsNotBase64Fails() throws Exception {
        replyWith(ids -> records(
                "{\"recordId\":\"" + ids.get(0) + "\",\"result\":\"Ok\",\"data\":\"not base64 %%%\"}",
                ok(ids.get(1), "kept")));

        FirehoseLambdaTransformer.Outcome outcome =
                transform(stream(lambdaProcessor()), record("a"), record("b"));

        assertEquals(List.of("kept"), delivered(outcome));
        assertEquals(1, outcome.failedRecords());
        JsonNode line = errorLines().get(0);
        assertEquals("Lambda.InvalidReturnFormat", line.get("errorCode").asText());
        assertTrue(line.get("errorMessage").asText().startsWith("The data field must be base64 encoded"));
        assertEquals(encode("a"), line.get("rawData").asText());
    }

    @Test
    void anUnknownResultValueFails() throws Exception {
        replyWith(ids -> records(outcome(ids.get(0), "Maybe")));

        transform(stream(lambdaProcessor()), record("a"));

        assertEquals("Lambda.InvalidReturnFormat", errorLines().get(0).get("errorCode").asText());
    }

    // ── failures of the invocation itself ────────────────────────────────────

    @Test
    void aFunctionErrorIsRetriedAndThenFailsTheWholeBatch() throws Exception {
        InvokeResult errored = new InvokeResult();
        errored.setStatusCode(200);
        errored.setFunctionError("Unhandled");
        when(lambdaService.invokeArn(anyString(), any(), any())).thenReturn(errored);

        FirehoseLambdaTransformer.Outcome outcome =
                transform(stream(lambdaProcessor("NumberOfRetries", "2")), record("a"), record("b"));

        assertTrue(outcome.records().isEmpty());
        assertEquals(2, outcome.failedRecords());
        verify(lambdaService, times(3)).invokeArn(anyString(), any(), any());
        JsonNode line = errorLines().get(0);
        assertEquals("Lambda.FunctionError", line.get("errorCode").asText());
        assertEquals(3, line.get("attemptsMade").asInt());
    }

    @Test
    void anUninvokableFunctionIsRetriedTheSameWay() throws Exception {
        when(lambdaService.invokeArn(anyString(), any(), any()))
                .thenThrow(new AwsException("ResourceNotFoundException", "Function not found", 404));

        transform(stream(lambdaProcessor("NumberOfRetries", "1")), record("a"));

        verify(lambdaService, times(2)).invokeArn(anyString(), any(), any());
        assertEquals("Lambda.FunctionError", errorLines().get(0).get("errorCode").asText());
    }

    @Test
    void aResponseCarryingNoRecordsIsNotRetried() throws Exception {
        replyWith(ids -> "{}");

        transform(stream(lambdaProcessor("NumberOfRetries", "5")), record("a"));

        verify(lambdaService, times(1)).invokeArn(anyString(), any(), any());
        JsonNode line = errorLines().get(0);
        assertEquals("Lambda.MissingRecordId", line.get("errorCode").asText());
        assertEquals(1, line.get("attemptsMade").asInt());
    }

    @Test
    void anUnparseableResponseFailsEveryRecordWithoutRetrying() throws Exception {
        replyWith(ids -> "not json at all");

        transform(stream(lambdaProcessor()), record("a"));

        verify(lambdaService, times(1)).invokeArn(anyString(), any(), any());
        assertEquals("Lambda.MissingRecordId", errorLines().get(0).get("errorCode").asText());
    }

    @Test
    void anAbsentNumberOfRetriesFallsBackToTheThreeAwsDefaults() {
        InvokeResult errored = new InvokeResult();
        errored.setFunctionError("Unhandled");
        when(lambdaService.invokeArn(anyString(), any(), any())).thenReturn(errored);

        transform(stream(lambdaProcessor()), record("a"));

        verify(lambdaService, times(4)).invokeArn(anyString(), any(), any());
    }

    /**
     * AWS stores a NumberOfRetries it never range-checks, so the largest one a caller can
     * write has to stay a bounded number of invocations rather than overflowing the
     * attempt count or occupying the flusher thread indefinitely.
     */
    @Test
    void aRetryCountLargerThanFlociHonoursIsCapped() throws Exception {
        InvokeResult errored = new InvokeResult();
        errored.setFunctionError("Unhandled");
        when(lambdaService.invokeArn(anyString(), any(), any())).thenReturn(errored);

        transform(stream(lambdaProcessor("NumberOfRetries", String.valueOf(Integer.MAX_VALUE))), record("a"));

        verify(lambdaService, times(101)).invokeArn(anyString(), any(), any());
        assertEquals(101, errorLines().get(0).get("attemptsMade").asInt());
    }

    @Test
    void aNegativeRetryCountInvokesOnce() {
        replyWith(ids -> records(ok(ids.get(0), "one")));

        transform(stream(lambdaProcessor("NumberOfRetries", "-5")), record("a"));

        verify(lambdaService, times(1)).invokeArn(anyString(), any(), any());
    }

    // ── error object ─────────────────────────────────────────────────────────

    @Test
    void theErrorObjectFollowsTheProbedMemberOrder() throws Exception {
        replyWith(ids -> records(outcome(ids.get(0), "ProcessingFailed")));

        transform(stream(lambdaProcessor()), record("a"));

        JsonNode line = errorLines().get(0);
        assertEquals(List.of("rawData", "errorCode", "errorMessage", "attemptsMade",
                        "arrivalTimestamp", "attemptEndingTimestamp", "lambdaARN"),
                fieldNames(line));
        assertEquals(FUNCTION_ARN, line.get("lambdaARN").asText());
        assertEquals(DELIVERY_TIME.toEpochMilli(), line.get("arrivalTimestamp").asLong());
        assertEquals(DELIVERY_TIME.toEpochMilli(), line.get("attemptEndingTimestamp").asLong());
    }

    @Test
    void everyFailedRecordGetsItsOwnLineInOneErrorObject() throws Exception {
        replyWith(ids -> records(outcome(ids.get(0), "ProcessingFailed"), outcome(ids.get(1), "ProcessingFailed")));

        FirehoseLambdaTransformer.Outcome outcome =
                transform(stream(lambdaProcessor()), record("a"), record("b"));

        assertEquals(2, outcome.failedRecords());
        List<JsonNode> lines = errorLines();
        assertEquals(2, lines.size());
        assertEquals(encode("a"), lines.get(0).get("rawData").asText());
        assertEquals(encode("b"), lines.get(1).get("rawData").asText());
    }

    @Test
    void theErrorOutputKeyResolvesTheErrorOutputTypeExpression() {
        replyWith(ids -> records(outcome(ids.get(0), "ProcessingFailed")));

        FirehoseLambdaTransformer.Outcome outcome = transform(stream(lambdaProcessor()), record("a"));

        assertTrue(outcome.errorKey().startsWith("errors/processing-failed/stream-"),
                "error output key was " + outcome.errorKey());
    }

    @Test
    void theBatchIsWrittenToTheDestinationBucket() {
        replyWith(ids -> records(outcome(ids.get(0), "ProcessingFailed")));

        transform(stream(lambdaProcessor()), record("a"));

        verify(s3Service).putObject(eq(BUCKET), anyString(), any(), eq("application/octet-stream"),
                eq(Map.of()));
    }
}
