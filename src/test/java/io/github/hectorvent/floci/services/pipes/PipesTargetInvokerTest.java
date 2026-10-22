package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.pipes.model.DesiredState;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipesTargetInvokerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private LambdaService lambdaService;
    @Mock private SqsService sqsService;
    @Mock private SnsService snsService;
    @Mock private EventBridgeService eventBridgeService;
    @Mock private StepFunctionsService stepFunctionsService;
    @Mock private KinesisService kinesisService;
    @Mock private EmulatorConfig config;

    private PipesTargetInvoker invoker;

    @BeforeEach
    void setUp() {
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        invoker = new PipesTargetInvoker(lambdaService, sqsService, snsService,
                eventBridgeService, stepFunctionsService, kinesisService, MAPPER, config);
    }

    private Pipe createPipe(String targetArn, ObjectNode targetParameters) {
        Pipe pipe = new Pipe();
        pipe.setName("test-pipe");
        pipe.setArn("arn:aws:pipes:us-east-1:000000000000:pipe/test-pipe");
        pipe.setSource("arn:aws:sqs:us-east-1:000000000000:source");
        pipe.setTarget(targetArn);
        pipe.setDesiredState(DesiredState.RUNNING);
        pipe.setTargetParameters(targetParameters);
        return pipe;
    }

    @Test
    void inputTemplate_replacesPlaceholders() {
        String region = "us-east-1";
        ObjectNode tp = MAPPER.createObjectNode();
        tp.put("InputTemplate", "{\"id\": <$.messageId>, \"content\": <$.body>}");

        Pipe pipe = createPipe("arn:aws:sqs:" + region + ":000000000000:target", tp);
        String payload = "{\"messageId\": \"msg-123\", \"body\": \"hello world\"}";

        invoker.invoke(pipe, payload, region);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sqsService).sendMessage(anyString(), captor.capture(), isNull(), isNull(), isNull(), eq(region));
        String sent = captor.getValue();
        assertEquals("{\"id\": \"msg-123\", \"content\": \"hello world\"}", sent);
    }

    @Test
    void inputTemplate_missingFieldReplacesWithEmpty() {
        String region = "us-east-1";
        ObjectNode tp = MAPPER.createObjectNode();
        tp.put("InputTemplate", "{\"id\": \"<$.nonexistent>\"}");

        Pipe pipe = createPipe("arn:aws:sqs:" + region + ":000000000000:target", tp);
        String payload = "{\"messageId\": \"msg-123\"}";

        invoker.invoke(pipe, payload, region);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sqsService).sendMessage(anyString(), captor.capture(), isNull(), isNull(), isNull(), eq(region));
        assertEquals("{\"id\": \"\"}", captor.getValue());
    }

    @Test
    void noInputTemplate_passesPayloadUnchanged() {
        String region = "us-east-1";

        Pipe pipe = createPipe("arn:aws:sqs:" + region + ":000000000000:target", null);
        String payload = "{\"Records\": []}";

        invoker.invoke(pipe, payload, region);

        verify(sqsService).sendMessage(anyString(), eq(payload), isNull(), isNull(), isNull(), eq(region));
    }

    @Test
    void invoke_sqsTargetLeavesTheDelayToTheQueue() {
        Pipe pipe = createPipe("arn:aws:sqs:us-east-1:000000000000:target", null);

        invoker.invoke(pipe, "{\"messageId\":\"m1\"}", "us-east-1");

        verify(sqsService).sendMessage(eq("http://localhost:4566/000000000000/target"), eq("{\"messageId\":\"m1\"}"),
                isNull(), isNull(), isNull(), eq("us-east-1"));
    }

    @Test
    void invoke_fifoSqsTargetSendsTheMessageGroupAndDeduplicationIds() {
        ObjectNode tp = MAPPER.createObjectNode();
        tp.putObject("SqsQueueParameters").put("MessageGroupId", "g1").put("MessageDeduplicationId", "d1");
        Pipe pipe = createPipe("arn:aws:sqs:us-east-1:000000000000:orders.fifo", tp);

        invoker.invoke(pipe, "{\"messageId\":\"m1\"}", "us-east-1");

        verify(sqsService).sendMessage(anyString(), eq("{\"messageId\":\"m1\"}"),
                isNull(), eq("g1"), eq("d1"), eq("us-east-1"));
    }

    @Test
    void invoke_fifoSqsTargetResolvesJsonPathIdsFromTheUntransformedEvent() {
        ObjectNode tp = MAPPER.createObjectNode();
        tp.putObject("SqsQueueParameters")
                .put("MessageGroupId", "$.body.customerId")
                .put("MessageDeduplicationId", "$.messageId");
        tp.put("InputTemplate", "{\"order\": <$.body.orderId>}");
        Pipe pipe = createPipe("arn:aws:sqs:us-east-1:000000000000:orders.fifo", tp);

        invoker.invoke(pipe,
                "{\"messageId\":\"m1\",\"body\":\"{\\\"customerId\\\":\\\"c-7\\\",\\\"orderId\\\":\\\"o-1\\\"}\"}",
                "us-east-1");

        verify(sqsService).sendMessage(anyString(), eq("{\"order\": \"o-1\"}"),
                isNull(), eq("c-7"), eq("m1"), eq("us-east-1"));
    }

    @Test
    void invoke_keepsTheQualifierOfTheTargetFunctionArn() {
        Pipe pipe = createPipe("arn:aws:lambda:us-east-1:000000000000:function:my-fn:PROD", null);
        when(lambdaService.invoke(anyString(), anyString(), any(byte[].class), any(InvocationType.class)))
                .thenReturn(new io.github.hectorvent.floci.services.lambda.model.InvokeResult(200, null, "{}".getBytes(), null, "req"));

        invoker.invoke(pipe, "{}", "us-east-1");

        verify(lambdaService).invoke(eq("us-east-1"), eq("my-fn:PROD"), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void invoke_lambdaTargetAppliesTheInputTemplateToEachRecordOfTheBatch() {
        ObjectNode tp = MAPPER.createObjectNode();
        tp.put("InputTemplate", "{\"id\": <$.messageId>}");
        Pipe pipe = createPipe("arn:aws:lambda:us-east-1:000000000000:function:my-fn", tp);
        when(lambdaService.invoke(anyString(), anyString(), any(byte[].class), any(InvocationType.class)))
                .thenReturn(new InvokeResult(200, null, "{}".getBytes(), null, "req"));

        invoker.invoke(pipe, "[{\"messageId\":\"m1\"},{\"messageId\":\"m2\"}]", "us-east-1");

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invoke(eq("us-east-1"), eq("my-fn"), payload.capture(), eq(InvocationType.RequestResponse));
        assertEquals("[{\"id\":\"m1\"},{\"id\":\"m2\"}]",
                new String(payload.getValue(), StandardCharsets.UTF_8));
    }

    @Test
    void invoke_stepFunctionsTargetAppliesTheInputTemplateToEachRecordOfTheBatch() {
        ObjectNode tp = MAPPER.createObjectNode();
        tp.put("InputTemplate", "{\"id\": <$.messageId>}");
        String stateMachineArn = "arn:aws:states:us-east-1:000000000000:stateMachine:target";
        Pipe pipe = createPipe(stateMachineArn, tp);

        invoker.invoke(pipe, "[{\"messageId\":\"m1\"},{\"messageId\":\"m2\"}]", "us-east-1");

        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(stepFunctionsService).startExecution(eq(stateMachineArn), anyString(), input.capture(), eq("us-east-1"));
        assertEquals("[{\"id\":\"m1\"},{\"id\":\"m2\"}]", input.getValue());
    }

    @Test
    void invoke_batchTemplateOutputThatIsNotJsonBecomesAStringRecord() {
        ObjectNode tp = MAPPER.createObjectNode();
        tp.put("InputTemplate", "<$.count> items");
        String stateMachineArn = "arn:aws:states:us-east-1:000000000000:stateMachine:target";
        Pipe pipe = createPipe(stateMachineArn, tp);

        invoker.invoke(pipe, "[{\"count\":42}]", "us-east-1");

        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(stepFunctionsService).startExecution(eq(stateMachineArn), anyString(), input.capture(), eq("us-east-1"));
        assertEquals("[\"42 items\"]", input.getValue());
    }

    @Test
    void invoke_unsupportedTargetThrowsSoTheBatchIsNotConsumed() {
        String firehoseArn = "arn:aws:firehose:us-east-1:000000000000:deliverystream/out";
        Pipe pipe = createPipe(firehoseArn, null);

        AwsException e = assertThrows(AwsException.class, () -> invoker.invoke(pipe, "{}", "us-east-1"));

        assertTrue(e.getMessage().contains(firehoseArn), "the error names the target, was: " + e.getMessage());
    }

    @Test
    void invoke_kinesisTargetPutsTheRecordUnderTheLiteralPartitionKey() {
        ObjectNode tp = MAPPER.createObjectNode();
        tp.putObject("KinesisStreamParameters").put("PartitionKey", "orders");
        Pipe pipe = createPipe("arn:aws:kinesis:eu-west-1:111111111111:stream/out", tp);

        invoker.invoke(pipe, "{\"messageId\":\"m1\"}", "us-east-1");

        verify(kinesisService).putRecordForAccount(eq("111111111111"), eq("out"),
                aryEq("{\"messageId\":\"m1\"}".getBytes(StandardCharsets.UTF_8)), eq("orders"), eq("eu-west-1"));
    }

    @Test
    void invoke_kinesisTargetResolvesAJsonPathPartitionKeyFromTheUntransformedEvent() {
        ObjectNode tp = MAPPER.createObjectNode();
        tp.putObject("KinesisStreamParameters").put("PartitionKey", "$.body.someKey");
        tp.put("InputTemplate", "{\"id\": <$.messageId>}");
        Pipe pipe = createPipe("arn:aws:kinesis:us-east-1:000000000000:stream/out", tp);

        invoker.invoke(pipe, "{\"messageId\":\"m1\",\"body\":\"{\\\"someKey\\\":\\\"someValue\\\"}\"}", "us-east-1");

        verify(kinesisService).putRecordForAccount(eq("000000000000"), eq("out"),
                aryEq("{\"id\": \"m1\"}".getBytes(StandardCharsets.UTF_8)), eq("someValue"), eq("us-east-1"));
    }

    @Test
    void invoke_kinesisTargetWithoutAResolvablePartitionKeyThrows() {
        Pipe noParameters = createPipe("arn:aws:kinesis:us-east-1:000000000000:stream/out", null);
        ObjectNode tp = MAPPER.createObjectNode();
        tp.putObject("KinesisStreamParameters").put("PartitionKey", "$.body.missing");
        Pipe unmatchedPath = createPipe("arn:aws:kinesis:us-east-1:000000000000:stream/out", tp);

        assertThrows(AwsException.class, () -> invoker.invoke(noParameters, "{\"body\":\"{}\"}", "us-east-1"));
        assertThrows(AwsException.class, () -> invoker.invoke(unmatchedPath, "{\"body\":\"{}\"}", "us-east-1"));
        verifyNoInteractions(kinesisService);
    }

    @Test
    void invoke_throwsOnDeliveryFailure() {
        Pipe pipe = createPipe("arn:aws:lambda:us-east-1:000000000000:function:my-fn", null);
        doThrow(new RuntimeException("boom")).when(lambdaService)
                .invoke(anyString(), anyString(), any(byte[].class), any(InvocationType.class));

        assertThrows(RuntimeException.class, () ->
                invoker.invoke(pipe, "{}", "us-east-1"));
    }

    @Test
    void inputTemplate_objectValuePreservedAsJson() {
        String region = "us-east-1";
        ObjectNode tp = MAPPER.createObjectNode();
        tp.put("InputTemplate", "{\"data\": <$.nested>}");

        Pipe pipe = createPipe("arn:aws:sqs:" + region + ":000000000000:target", tp);
        String payload = "{\"nested\": {\"key\": \"value\"}}";

        invoker.invoke(pipe, payload, region);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sqsService).sendMessage(anyString(), captor.capture(), isNull(), isNull(), isNull(), eq(region));
        assertEquals("{\"data\": {\"key\":\"value\"}}", captor.getValue());
    }

    // ──────────────────────────── applyInputTemplate unit ────────────────────────────

    @Test
    void applyInputTemplate_noPlaceholders_returnsTemplate() {
        String result = invoker.applyInputTemplate("static text", "{}");
        assertEquals("static text", result);
    }

    @Test
    void extractJsonPath_returnsTextValue() {
        assertEquals("\"hello\"", invoker.extractJsonPath("$.body", "{\"body\": \"hello\"}"));
    }

    @Test
    void extractJsonPath_returnsNullForMissing() {
        assertNull(invoker.extractJsonPath("$.missing", "{\"body\": \"hello\"}"));
    }

    @Test
    void extractJsonPath_arrayIndex() {
        String json = "{\"Records\": [{\"body\": \"first\"}, {\"body\": \"second\"}]}";
        assertEquals("\"first\"", invoker.extractJsonPath("$.Records[0].body", json));
        assertEquals("\"second\"", invoker.extractJsonPath("$.Records[1].body", json));
    }

    @Test
    void extractJsonPath_numericValue() {
        assertEquals("42", invoker.extractJsonPath("$.count", "{\"count\": 42}"));
    }

    @Test
    void extractJsonPath_booleanValue() {
        assertEquals("true", invoker.extractJsonPath("$.active", "{\"active\": true}"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventBridge_usesEventBridgeEventBusParameters() {
        ObjectNode tp = MAPPER.createObjectNode();
        ObjectNode ebParams = tp.putObject("EventBridgeEventBusParameters");
        ebParams.put("Source", "registration-service");
        ebParams.put("DetailType", "USER_REGISTRATION_COMPLETED");

        Pipe pipe = createPipe("arn:aws:events:us-east-1:000000000000:event-bus/my-bus", tp);
        invoker.invoke(pipe, "{\"user\": \"123\"}", "us-east-1");

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventBridgeService).putEvents(captor.capture(), eq("us-east-1"));
        Map<String, Object> entry = captor.getValue().get(0);
        assertEquals("my-bus", entry.get("EventBusName"));
        assertEquals("registration-service", entry.get("Source"));
        assertEquals("USER_REGISTRATION_COMPLETED", entry.get("DetailType"));
        assertEquals("{\"user\": \"123\"}", entry.get("Detail"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventBridge_fallsBackToDefaults() {
        Pipe pipe = createPipe("arn:aws:events:us-east-1:000000000000:event-bus/default", null);
        invoker.invoke(pipe, "{}", "us-east-1");

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventBridgeService).putEvents(captor.capture(), eq("us-east-1"));
        Map<String, Object> entry = captor.getValue().get(0);
        assertEquals("default", entry.get("EventBusName"));
        assertEquals("aws.pipes", entry.get("Source"));
        assertEquals("PipeForwarded", entry.get("DetailType"));
    }

    @Test
    void inputTemplate_numericNotQuoted() {
        String result = invoker.applyInputTemplate("{\"count\": <$.count>}", "{\"count\": 42}");
        assertEquals("{\"count\": 42}", result);
    }

    // ──────────────────────────── nested JSON string resolution ────────────────────────────

    @Test
    void extractJsonPath_nestedJsonString_resolvesField() {
        String json = "{\"body\": \"{\\\"message\\\": \\\"hello\\\", \\\"type\\\": \\\"greeting\\\"}\"}";
        assertEquals("\"hello\"", invoker.extractJsonPath("$.body.message", json));
        assertEquals("\"greeting\"", invoker.extractJsonPath("$.body.type", json));
    }

    @Test
    void extractJsonPath_nestedJsonString_missingNestedField() {
        String json = "{\"body\": \"{\\\"message\\\": \\\"hello\\\"}\"}";
        assertNull(invoker.extractJsonPath("$.body.nonexistent", json));
    }

    @Test
    void extractJsonPath_nestedJsonString_nonJsonStringReturnsNull() {
        String json = "{\"body\": \"plain text\"}";
        assertNull(invoker.extractJsonPath("$.body.message", json));
    }

    @Test
    void extractJsonPath_nestedJsonString_objectValue() {
        String json = "{\"body\": \"{\\\"data\\\": {\\\"id\\\": 1}}\"}";
        assertEquals("{\"id\":1}", invoker.extractJsonPath("$.body.data", json));
    }

    @Test
    void inputTemplate_nestedJsonString_endToEnd() {
        String region = "us-east-1";
        ObjectNode tp = MAPPER.createObjectNode();
        tp.put("InputTemplate",
                "{\"message\": <$.body.message>, \"messageType\": <$.body.messageType>}");

        Pipe pipe = createPipe("arn:aws:sqs:us-east-1:000000000000:target", tp);
        String payload = "{\"body\": \"{\\\"message\\\": \\\"user registered\\\", \\\"messageType\\\": \\\"REGISTRATION\\\"}\"}";

        invoker.invoke(pipe, payload, region);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sqsService).sendMessage(anyString(), captor.capture(), isNull(), isNull(), isNull(), eq(region));
        assertEquals(
                "{\"message\": \"user registered\", \"messageType\": \"REGISTRATION\"}",
                captor.getValue());
    }

    // --- Enrichment: AWS Pipes invokes the enrichment Lambda with the events and forwards its response ---

    private Pipe enrichmentPipe(String region, String enrichmentArn) {
        Pipe pipe = createPipe("arn:aws:states:" + region + ":000000000000:stateMachine:target", null);
        pipe.setEnrichment(enrichmentArn);
        return pipe;
    }

    @Test
    void applyEnrichment_invokesLambdaAndReturnsResponse() {
        String region = "us-east-1";
        Pipe pipe = enrichmentPipe(region, "arn:aws:lambda:" + region + ":000000000000:function:enrich-fn");
        byte[] resp = "{\"systemId\":\"S1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(lambdaService.invoke(eq(region), eq("enrich-fn"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new io.github.hectorvent.floci.services.lambda.model.InvokeResult(200, null, resp, null, "req"));

        String out = invoker.applyEnrichment(pipe, "[{\"body\":\"{}\"}]", region);

        assertEquals("{\"systemId\":\"S1\"}", out);
        ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invoke(eq(region), eq("enrich-fn"), payloadCaptor.capture(), eq(InvocationType.RequestResponse));
        assertEquals("[{\"body\":\"{}\"}]",
                new String(payloadCaptor.getValue(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void applyEnrichment_appliesTheEnrichmentInputTemplateToEachRecordOfTheBatch() {
        String region = "us-east-1";
        Pipe pipe = enrichmentPipe(region, "arn:aws:lambda:" + region + ":000000000000:function:enrich-fn");
        ObjectNode ep = MAPPER.createObjectNode();
        ep.put("InputTemplate", "{\"id\": <$.messageId>}");
        pipe.setEnrichmentParameters(ep);
        when(lambdaService.invoke(eq(region), eq("enrich-fn"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, null, "[{}]".getBytes(), null, "req"));

        invoker.applyEnrichment(pipe, "[{\"messageId\":\"m1\"},{\"messageId\":\"m2\"}]", region);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invoke(eq(region), eq("enrich-fn"), payload.capture(), eq(InvocationType.RequestResponse));
        assertEquals("[{\"id\":\"m1\"},{\"id\":\"m2\"}]",
                new String(payload.getValue(), StandardCharsets.UTF_8));
    }

    @Test
    void applyEnrichment_keepsTheQualifierOfTheFunctionArn() {
        String region = "us-east-1";
        Pipe pipe = enrichmentPipe(region, "arn:aws:lambda:" + region + ":000000000000:function:enrich-fn:PROD");
        when(lambdaService.invoke(eq(region), eq("enrich-fn:PROD"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new io.github.hectorvent.floci.services.lambda.model.InvokeResult(200, null, "{}".getBytes(), null, "req"));

        invoker.applyEnrichment(pipe, "[]", region);

        verify(lambdaService).invoke(eq(region), eq("enrich-fn:PROD"), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void applyEnrichment_noEnrichmentReturnsPayloadUnchanged() {
        Pipe pipe = createPipe("arn:aws:states:us-east-1:000000000000:stateMachine:target", null);
        String out = invoker.applyEnrichment(pipe, "[{\"x\":1}]", "us-east-1");
        assertEquals("[{\"x\":1}]", out);
        verifyNoInteractions(lambdaService);
    }

    @Test
    void applyEnrichment_emptyResponseReturnsNull() {
        String region = "us-east-1";
        Pipe pipe = enrichmentPipe(region, "arn:aws:lambda:" + region + ":000000000000:function:enrich-fn");
        when(lambdaService.invoke(eq(region), eq("enrich-fn"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new io.github.hectorvent.floci.services.lambda.model.InvokeResult(200, null, new byte[0], null, "req"));
        assertNull(invoker.applyEnrichment(pipe, "[]", region));
    }

    @Test
    void applyEnrichment_emptyObjectOrArrayResponseSkipsTarget() {
        // AWS skips the target when the enrichment returns {} or [] (whitespace variants included).
        String region = "us-east-1";
        Pipe pipe = enrichmentPipe(region, "arn:aws:lambda:" + region + ":000000000000:function:enrich-fn");
        for (String body : new String[]{"{}", "[]", "  { }  ", "[ ]"}) {
            when(lambdaService.invoke(eq(region), eq("enrich-fn"), any(byte[].class), eq(InvocationType.RequestResponse)))
                    .thenReturn(new io.github.hectorvent.floci.services.lambda.model.InvokeResult(
                            200, null, body.getBytes(java.nio.charset.StandardCharsets.UTF_8), null, "req"));
            assertNull(invoker.applyEnrichment(pipe, "[]", region),
                    "enrichment response " + body + " should skip the target");
        }
    }

    @Test
    void applyEnrichment_singleElementArrayResponseInvokesTarget() {
        // [{}] is the explicit "invoke the target with an empty-payload element" form — not skipped.
        String region = "us-east-1";
        Pipe pipe = enrichmentPipe(region, "arn:aws:lambda:" + region + ":000000000000:function:enrich-fn");
        when(lambdaService.invoke(eq(region), eq("enrich-fn"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new io.github.hectorvent.floci.services.lambda.model.InvokeResult(
                        200, null, "[{}]".getBytes(java.nio.charset.StandardCharsets.UTF_8), null, "req"));
        assertEquals("[{}]", invoker.applyEnrichment(pipe, "[]", region));
    }

    @Test
    void applyEnrichment_unsupportedTypeThrows() {
        // Non-Lambda enrichment (e.g. an API destination) is not emulated — applyEnrichment must
        // fail so the batch is routed to the DLQ rather than delivered unenriched.
        String region = "us-east-1";
        Pipe pipe = enrichmentPipe(region, "arn:aws:events:" + region + ":000000000000:api-destination/foo");
        assertThrows(RuntimeException.class, () -> invoker.applyEnrichment(pipe, "[{\"x\":1}]", region));
        verifyNoInteractions(lambdaService);
    }

    @Test
    void applyEnrichment_lambdaFunctionErrorThrows() {
        String region = "us-east-1";
        Pipe pipe = enrichmentPipe(region, "arn:aws:lambda:" + region + ":000000000000:function:enrich-fn");
        when(lambdaService.invoke(eq(region), eq("enrich-fn"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new io.github.hectorvent.floci.services.lambda.model.InvokeResult(200, "Unhandled", "boom".getBytes(), null, "req"));
        assertThrows(RuntimeException.class, () -> invoker.applyEnrichment(pipe, "[]", region));
    }

    @Test
    void invoke_lambdaTargetReturnsTheReportedBatchItemFailures() {
        Pipe pipe = createPipe("arn:aws:lambda:us-east-1:000000000000:function:my-fn", null);
        when(lambdaService.invoke(eq("us-east-1"), eq("my-fn"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, null,
                        "{\"batchItemFailures\":[{\"itemIdentifier\":\"m2\"},{\"itemIdentifier\":\"m3\"}]}"
                                .getBytes(StandardCharsets.UTF_8), null, "req"));

        assertEquals(List.of("m2", "m3"), invoker.invoke(pipe, "[{\"messageId\":\"m1\"}]", "us-east-1"));
    }

    @Test
    void invoke_lambdaTargetResponseWithoutFailuresIsACompleteSuccess() {
        Pipe pipe = createPipe("arn:aws:lambda:us-east-1:000000000000:function:my-fn", null);
        for (String response : new String[]{"", "null", "{}", "\"done\"", "{\"batchItemFailures\":null}",
                "{\"batchItemFailures\":[]}"}) {
            when(lambdaService.invoke(eq("us-east-1"), eq("my-fn"), any(byte[].class), eq(InvocationType.RequestResponse)))
                    .thenReturn(new InvokeResult(200, null, response.getBytes(StandardCharsets.UTF_8), null, "req"));

            assertEquals(List.of(), invoker.invoke(pipe, "[]", "us-east-1"), "response " + response);
        }
    }

    @Test
    void invoke_lambdaTargetMalformedBatchItemFailuresFailTheWholeBatch() {
        Pipe pipe = createPipe("arn:aws:lambda:us-east-1:000000000000:function:my-fn", null);
        for (String response : new String[]{"{\"batchItemFailures\":{}}",
                "{\"batchItemFailures\":[{\"itemIdentifier\":\"\"}]}",
                "{\"batchItemFailures\":[{\"itemIdentifier\":null}]}",
                "{\"batchItemFailures\":[{\"messageId\":\"m1\"}]}"}) {
            when(lambdaService.invoke(eq("us-east-1"), eq("my-fn"), any(byte[].class), eq(InvocationType.RequestResponse)))
                    .thenReturn(new InvokeResult(200, null, response.getBytes(StandardCharsets.UTF_8), null, "req"));

            assertThrows(AwsException.class, () -> invoker.invoke(pipe, "[]", "us-east-1"), "response " + response);
        }
    }

    @Test
    void invoke_lambdaTargetFunctionErrorThrows() {
        // A target Lambda that returns a FunctionError is a failed delivery — invoke must throw so the
        // poller routes the source record to the DLQ instead of silently deleting it.
        String region = "us-east-1";
        Pipe pipe = createPipe("arn:aws:lambda:" + region + ":000000000000:function:my-fn", null);
        when(lambdaService.invoke(eq(region), eq("my-fn"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new io.github.hectorvent.floci.services.lambda.model.InvokeResult(200, "Unhandled", "boom".getBytes(), null, "req"));
        assertThrows(RuntimeException.class, () -> invoker.invoke(pipe, "{}", region));
    }

    @Test
    void eventBridgeTarget_succeedsWhenPutEventsReportsNoFailures() {
        String region = "us-east-1";
        String busArn = "arn:aws:events:" + region + ":000000000000:event-bus/test-bus";

        Pipe pipe = createPipe(busArn, null);
        String payload = "{\"key\":\"value\"}";

        EventBridgeService.PutEventsResult success = new EventBridgeService.PutEventsResult(
                0,
                List.of(Map.of("EventId", "evt-123")));

        when(eventBridgeService.putEvents(anyList(), eq(region))).thenReturn(success);

        assertDoesNotThrow(() -> invoker.invoke(pipe, payload, region));

        verify(eventBridgeService).putEvents(anyList(), eq(region));
    }

    @Test
    void eventBridgeTarget_throwsWhenPutEventsReportsFailure() {
        String region = "us-east-1";
        String busArn = "arn:aws:events:" + region + ":000000000000:event-bus/test-bus";

        Pipe pipe = createPipe(busArn, null);
        String payload = "not json at all";

        Map<String, String> failedEntry = new HashMap<>();
        failedEntry.put("ErrorCode", "MalformedDetail");
        failedEntry.put("ErrorMessage", "The JSON provided is not valid.");
        EventBridgeService.PutEventsResult failure = new EventBridgeService.PutEventsResult(1, List.of(failedEntry));

        when(eventBridgeService.putEvents(anyList(), eq(region))).thenReturn(failure);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> invoker.invoke(pipe, payload, region));

        assertTrue(ex.getMessage().contains("MalformedDetail"),
                "error message should surface the EventBridge failure code; was: " + ex.getMessage());
    }
}
