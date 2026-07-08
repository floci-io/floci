package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
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
    @Mock private EmulatorConfig config;

    private PipesTargetInvoker invoker;

    @BeforeEach
    void setUp() {
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        invoker = new PipesTargetInvoker(lambdaService, sqsService, snsService,
                eventBridgeService, stepFunctionsService, MAPPER, config);
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
        verify(sqsService).sendMessage(anyString(), captor.capture(), eq(0), eq(region));
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
        verify(sqsService).sendMessage(anyString(), captor.capture(), eq(0), eq(region));
        assertEquals("{\"id\": \"\"}", captor.getValue());
    }

    @Test
    void noInputTemplate_passesPayloadUnchanged() {
        String region = "us-east-1";

        Pipe pipe = createPipe("arn:aws:sqs:" + region + ":000000000000:target", null);
        String payload = "{\"Records\": []}";

        invoker.invoke(pipe, payload, region);

        verify(sqsService).sendMessage(anyString(), eq(payload), eq(0), eq(region));
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
        verify(sqsService).sendMessage(anyString(), captor.capture(), eq(0), eq(region));
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
        verify(sqsService).sendMessage(anyString(), captor.capture(), eq(0), eq(region));
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
    void applyEnrichment_qualifiedArnResolvesFunctionName() {
        String region = "us-east-1";
        Pipe pipe = enrichmentPipe(region, "arn:aws:lambda:" + region + ":000000000000:function:enrich-fn:$LATEST");
        when(lambdaService.invoke(eq(region), eq("enrich-fn"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new io.github.hectorvent.floci.services.lambda.model.InvokeResult(200, null, "{}".getBytes(), null, "req"));

        invoker.applyEnrichment(pipe, "[]", region);

        verify(lambdaService).invoke(eq(region), eq("enrich-fn"), any(byte[].class), eq(InvocationType.RequestResponse));
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
    void invoke_lambdaTargetFunctionErrorThrows() {
        // A target Lambda that returns a FunctionError is a failed delivery — invoke must throw so the
        // poller routes the source record to the DLQ instead of silently deleting it.
        String region = "us-east-1";
        Pipe pipe = createPipe("arn:aws:lambda:" + region + ":000000000000:function:my-fn", null);
        when(lambdaService.invoke(eq(region), eq("my-fn"), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new io.github.hectorvent.floci.services.lambda.model.InvokeResult(200, "Unhandled", "boom".getBytes(), null, "req"));
        assertThrows(RuntimeException.class, () -> invoker.invoke(pipe, "{}", region));
    }
}
