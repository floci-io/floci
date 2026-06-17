package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqsEventSourcePollerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private SqsEventSourcePoller poller;
    private SqsService sqsService;
    private LambdaExecutorService executorService;
    private LambdaFunctionStore functionStore;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambdaConfig = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambdaConfig);
        when(lambdaConfig.pollIntervalMs()).thenReturn(1000L);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        sqsService = mock(SqsService.class);
        executorService = mock(LambdaExecutorService.class);
        functionStore = mock(LambdaFunctionStore.class);

        poller = new SqsEventSourcePoller(
                mock(Vertx.class),
                sqsService,
                executorService,
                functionStore,
                mock(EsmStore.class),
                config,
                OBJECT_MAPPER
        );
    }

    @Test
    void buildSqsEventIncludesAllRequiredAttributes() throws Exception {
        Message msg = new Message();
        msg.setBody("{\"key\":\"value\"}");
        msg.setSentTimestamp(Instant.parse("2026-01-15T10:30:00Z"));

        EventSourceMapping esm = new EventSourceMapping();
        esm.setEventSourceArn("arn:aws:sqs:us-east-1:123456789012:my-queue");
        esm.setRegion("us-east-1");

        String event = poller.buildSqsEvent(List.of(msg), esm);
        JsonNode root = OBJECT_MAPPER.readTree(event);
        JsonNode record = root.get("Records").get(0);
        JsonNode attrs = record.get("attributes");

        assertNotNull(attrs.get("ApproximateReceiveCount"));
        assertNotNull(attrs.get("SentTimestamp"));
        assertNotNull(attrs.get("SenderId"));
        assertNotNull(attrs.get("ApproximateFirstReceiveTimestamp"));

        assertEquals("123456789012", attrs.get("SenderId").asText());
        assertEquals(String.valueOf(Instant.parse("2026-01-15T10:30:00Z").toEpochMilli()),
                attrs.get("SentTimestamp").asText());
        assertEquals("aws:sqs", record.get("eventSource").asText());
        assertEquals("arn:aws:sqs:us-east-1:123456789012:my-queue", record.get("eventSourceARN").asText());
        assertEquals("us-east-1", record.get("awsRegion").asText());
    }

    @Test
    void buildSqsEventUsesDefaultAccountWhenArnParsingFails() throws Exception {
        Message msg = new Message();
        msg.setBody("test");
        msg.setSentTimestamp(Instant.now());

        EventSourceMapping esm = new EventSourceMapping();
        esm.setEventSourceArn("invalid-arn");
        esm.setRegion("us-east-1");

        String event = poller.buildSqsEvent(List.of(msg), esm);
        JsonNode root = OBJECT_MAPPER.readTree(event);
        JsonNode attrs = root.get("Records").get(0).get("attributes");

        assertEquals("000000000000", attrs.get("SenderId").asText());
    }

    private EventSourceMapping esm() {
        EventSourceMapping esm = new EventSourceMapping();
        esm.setUuid("esm-uuid");
        esm.setAccountId("000000000000");
        esm.setRegion("us-east-1");
        esm.setFunctionName("throwfn");
        esm.setEventSourceArn("arn:aws:sqs:us-east-1:000000000000:esm-src");
        esm.setQueueUrl("http://localhost:4566/000000000000/esm-src");
        esm.setBatchSize(1);
        return esm;
    }

    private Message message(String id) {
        Message msg = new Message();
        msg.setMessageId(id);
        msg.setReceiptHandle("rh-" + id);
        msg.setBody("body-" + id);
        msg.setSentTimestamp(Instant.now());
        return msg;
    }

    @Test
    void failedInvocationReturnsMessagesToQueueImmediately() {
        EventSourceMapping esm = esm();
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("throwfn");
        fn.setTimeout(10);
        when(functionStore.getForAccount("000000000000", "us-east-1", "throwfn"))
                .thenReturn(Optional.of(fn));

        Message msg = message("m1");
        when(sqsService.receiveMessage(eq(esm.getQueueUrl()), anyInt(), anyInt(), anyInt(), eq("us-east-1")))
                .thenReturn(List.of(msg));

        InvokeResult failure = new InvokeResult();
        failure.setFunctionError("Handled");
        when(executorService.invoke(eq(fn), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(failure);

        poller.pollAndInvoke(esm);

        // The failed message must be made visible again (timeout 0) so the next poll
        // re-receives it and the queue's RedrivePolicy can move it to the DLQ.
        verify(sqsService, timeout(2000)).changeMessageVisibility(
                esm.getQueueUrl(), "rh-m1", 0, "us-east-1");
        verify(sqsService, never()).deleteMessage(any(), any(), any());
    }

    @Test
    void successfulInvocationDeletesMessagesAndDoesNotResetVisibility() {
        EventSourceMapping esm = esm();
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("throwfn");
        fn.setTimeout(10);
        when(functionStore.getForAccount("000000000000", "us-east-1", "throwfn"))
                .thenReturn(Optional.of(fn));

        Message msg = message("m1");
        when(sqsService.receiveMessage(eq(esm.getQueueUrl()), anyInt(), anyInt(), anyInt(), eq("us-east-1")))
                .thenReturn(List.of(msg));

        when(executorService.invoke(eq(fn), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult());

        poller.pollAndInvoke(esm);

        verify(sqsService, timeout(2000)).deleteMessage(esm.getQueueUrl(), "rh-m1", "us-east-1");
        verify(sqsService, never()).changeMessageVisibility(any(), any(), anyInt(), any());
    }
}
