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
        ObjectNode tp = MAPPER.createObjectNode();
        tp.put("InputTemplate", "{\"id\": <$.messageId>, \"content\": <$.body>}");

        Pipe pipe = createPipe("arn:aws:sqs:us-east-1:000000000000:target", tp);
        String payload = "{\"messageId\": \"msg-123\", \"body\": \"hello world\"}";

        invoker.invoke(pipe, payload, "us-east-1");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sqsService).sendMessage(anyString(), captor.capture(), eq(0));
        String sent = captor.getValue();
        assertEquals("{\"id\": \"msg-123\", \"content\": \"hello world\"}", sent);
    }

    @Test
    void inputTemplate_missingFieldReplacesWithEmpty() {
        ObjectNode tp = MAPPER.createObjectNode();
        tp.put("InputTemplate", "{\"id\": \"<$.nonexistent>\"}");

        Pipe pipe = createPipe("arn:aws:sqs:us-east-1:000000000000:target", tp);
        String payload = "{\"messageId\": \"msg-123\"}";

        invoker.invoke(pipe, payload, "us-east-1");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sqsService).sendMessage(anyString(), captor.capture(), eq(0));
        assertEquals("{\"id\": \"\"}", captor.getValue());
    }

    @Test
    void noInputTemplate_passesPayloadUnchanged() {
        Pipe pipe = createPipe("arn:aws:sqs:us-east-1:000000000000:target", null);
        String payload = "{\"Records\": []}";

        invoker.invoke(pipe, payload, "us-east-1");

        verify(sqsService).sendMessage(anyString(), eq(payload), eq(0));
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
        ObjectNode tp = MAPPER.createObjectNode();
        tp.put("InputTemplate", "{\"data\": <$.nested>}");

        Pipe pipe = createPipe("arn:aws:sqs:us-east-1:000000000000:target", tp);
        String payload = "{\"nested\": {\"key\": \"value\"}}";

        invoker.invoke(pipe, payload, "us-east-1");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sqsService).sendMessage(anyString(), captor.capture(), eq(0));
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
        ObjectNode tp = MAPPER.createObjectNode();
        tp.put("InputTemplate",
                "{\"message\": <$.body.message>, \"messageType\": <$.body.messageType>}");

        Pipe pipe = createPipe("arn:aws:sqs:us-east-1:000000000000:target", tp);
        String payload = "{\"body\": \"{\\\"message\\\": \\\"user registered\\\", \\\"messageType\\\": \\\"REGISTRATION\\\"}\"}";

        invoker.invoke(pipe, payload, "us-east-1");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sqsService).sendMessage(anyString(), captor.capture(), eq(0));
        assertEquals(
                "{\"message\": \"user registered\", \"messageType\": \"REGISTRATION\"}",
                captor.getValue());
    }
}
