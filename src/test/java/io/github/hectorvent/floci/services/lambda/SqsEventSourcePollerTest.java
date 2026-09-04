package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.pipes.PipesFilterMatcher;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
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
                OBJECT_MAPPER,
                new PipesFilterMatcher(OBJECT_MAPPER)
        );
    }

    @Test
    void buildSqsEventIncludesAllRequiredAttributes() throws Exception {
        Instant firstReceived = Instant.parse("2026-01-15T10:31:00Z");
        Message msg = new Message();
        msg.setBody("{\"key\":\"value\"}");
        msg.setSentTimestamp(Instant.parse("2026-01-15T10:30:00Z"));
        msg.setFirstReceiveTimestamp(firstReceived);

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
        assertEquals(String.valueOf(firstReceived.toEpochMilli()),
                attrs.get("ApproximateFirstReceiveTimestamp").asText());
        assertEquals("aws:sqs", record.get("eventSource").asText());
        assertEquals("arn:aws:sqs:us-east-1:123456789012:my-queue", record.get("eventSourceARN").asText());
        assertEquals("us-east-1", record.get("awsRegion").asText());

        // message with no attributes — messageAttributes must be an empty object, not absent
        assertTrue(record.get("messageAttributes").isObject());
        assertEquals(0, record.get("messageAttributes").size());
        assertNull(record.get("md5OfMessageAttributes"));
    }

    @Test
    void buildSqsEventPopulatesStringMessageAttribute() throws Exception {
        Message msg = new Message();
        msg.setBody("hello");
        msg.setSentTimestamp(Instant.now());

        io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue attr =
                new io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue("red", "String");
        msg.getMessageAttributes().put("color", attr);
        msg.updateMd5OfMessageAttributes();

        EventSourceMapping esm = new EventSourceMapping();
        esm.setEventSourceArn("arn:aws:sqs:us-east-1:123456789012:test-queue");
        esm.setRegion("us-east-1");

        String event = poller.buildSqsEvent(List.of(msg), esm);
        JsonNode record = OBJECT_MAPPER.readTree(event).get("Records").get(0);
        JsonNode msgAttrs = record.get("messageAttributes");

        assertTrue(msgAttrs.isObject(), "messageAttributes must be an object");
        assertEquals(1, msgAttrs.size());

        JsonNode color = msgAttrs.get("color");
        assertNotNull(color, "color attribute must be present");
        assertEquals("String", color.get("dataType").asText());
        assertEquals("red",    color.get("stringValue").asText());
        assertNull(color.get("binaryValue"));
        assertTrue(color.get("stringListValues").isArray());
        assertTrue(color.get("binaryListValues").isArray());

        // md5OfMessageAttributes must be propagated to the record
        assertNotNull(record.get("md5OfMessageAttributes"),
                "md5OfMessageAttributes must be present when attributes exist");
    }

    @Test
    void buildSqsEventPopulatesNumberMessageAttribute() throws Exception {
        Message msg = new Message();
        msg.setBody("order");
        msg.setSentTimestamp(Instant.now());

        io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue attr =
                new io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue("42", "Number");
        msg.getMessageAttributes().put("count", attr);

        EventSourceMapping esm = new EventSourceMapping();
        esm.setEventSourceArn("arn:aws:sqs:us-east-1:123456789012:num-queue");
        esm.setRegion("us-east-1");

        String event = poller.buildSqsEvent(List.of(msg), esm);
        JsonNode color = OBJECT_MAPPER.readTree(event)
                .get("Records").get(0).get("messageAttributes").get("count");

        assertEquals("Number", color.get("dataType").asText());
        assertEquals("42",     color.get("stringValue").asText());
    }

    @Test
    void buildSqsEventPopulatesBinaryMessageAttribute() throws Exception {
        Message msg = new Message();
        msg.setBody("bin");
        msg.setSentTimestamp(Instant.now());

        byte[] raw = {1, 2, 3};
        io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue attr =
                new io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue(raw, "Binary");
        msg.getMessageAttributes().put("data", attr);

        EventSourceMapping esm = new EventSourceMapping();
        esm.setEventSourceArn("arn:aws:sqs:us-east-1:123456789012:bin-queue");
        esm.setRegion("us-east-1");

        String event = poller.buildSqsEvent(List.of(msg), esm);
        JsonNode data = OBJECT_MAPPER.readTree(event)
                .get("Records").get(0).get("messageAttributes").get("data");

        assertEquals("Binary", data.get("dataType").asText());
        assertNull(data.get("stringValue"));
        assertEquals(
                java.util.Base64.getEncoder().encodeToString(raw),
                data.get("binaryValue").asText());
    }

    @Test
    void buildSqsEventHandlesMultipleAttributesAcrossMultipleMessages() throws Exception {
        io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue a1 =
                new io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue("v1", "String");
        io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue a2 =
                new io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue("v2", "String");

        Message msg1 = new Message();
        msg1.setBody("m1");
        msg1.setSentTimestamp(Instant.now());
        msg1.getMessageAttributes().put("attr1", a1);

        Message msg2 = new Message();
        msg2.setBody("m2");
        msg2.setSentTimestamp(Instant.now());
        msg2.getMessageAttributes().put("attr2", a2);

        EventSourceMapping esm = new EventSourceMapping();
        esm.setEventSourceArn("arn:aws:sqs:us-east-1:123456789012:multi-queue");
        esm.setRegion("us-east-1");

        String event = poller.buildSqsEvent(List.of(msg1, msg2), esm);
        JsonNode records = OBJECT_MAPPER.readTree(event).get("Records");

        assertEquals("v1", records.get(0).get("messageAttributes").get("attr1").get("stringValue").asText());
        assertEquals("v2", records.get(1).get("messageAttributes").get("attr2").get("stringValue").asText());
    }

    @Test
    void buildSqsEventIncludesFifoSystemAttributes() throws Exception {
        Message msg = new Message();
        msg.setBody("hello-fifo");
        msg.setSentTimestamp(Instant.parse("2026-01-15T10:30:00Z"));
        msg.setMessageGroupId("groupA");
        msg.setSequenceNumber(27);
        msg.setMessageDeduplicationId("6e809cbda0732ac4845916a59016f954");

        EventSourceMapping esm = new EventSourceMapping();
        esm.setEventSourceArn("arn:aws:sqs:us-east-1:123456789012:my-queue.fifo");
        esm.setRegion("us-east-1");

        String event = poller.buildSqsEvent(List.of(msg), esm);
        JsonNode attrs = OBJECT_MAPPER.readTree(event).get("Records").get(0).get("attributes");

        assertEquals("groupA", attrs.get("MessageGroupId").asText());
        assertEquals("27", attrs.get("SequenceNumber").asText());
        assertEquals("6e809cbda0732ac4845916a59016f954",
                attrs.get("MessageDeduplicationId").asText());
    }

    @Test
    void buildSqsEventOmitsFifoSystemAttributesForStandardQueueMessages() throws Exception {
        Message msg = new Message();
        msg.setBody("hello");
        msg.setSentTimestamp(Instant.parse("2026-01-15T10:30:00Z"));

        EventSourceMapping esm = new EventSourceMapping();
        esm.setEventSourceArn("arn:aws:sqs:us-east-1:123456789012:my-queue");
        esm.setRegion("us-east-1");

        String event = poller.buildSqsEvent(List.of(msg), esm);
        JsonNode attrs = OBJECT_MAPPER.readTree(event).get("Records").get(0).get("attributes");

        assertNull(attrs.get("MessageGroupId"));
        assertNull(attrs.get("SequenceNumber"));
        assertNull(attrs.get("MessageDeduplicationId"));
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
        msg.setFirstReceiveTimestamp(Instant.now());
        return msg;
    }

    @Test
    void failedInvocationReturnsMessagesToQueueUsingQueueVisibilityTimeout() {
        EventSourceMapping esm = esm();
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("throwfn");
        fn.setTimeout(10);
        when(functionStore.getForAccount("000000000000", "us-east-1", "throwfn"))
                .thenReturn(Optional.of(fn));

        Message msg = message("m1");
        when(sqsService.receiveMessage(eq(esm.getQueueUrl()), anyInt(), anyInt(), anyInt(), eq("us-east-1")))
                .thenReturn(List.of(msg));
        when(sqsService.getQueueAttributes(eq(esm.getQueueUrl()), any(), eq("us-east-1")))
                .thenReturn(Map.of("VisibilityTimeout", "2"));

        InvokeResult failure = new InvokeResult();
        failure.setFunctionError("Handled");
        when(executorService.invoke(eq(fn), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(failure);

        poller.pollAndInvoke(esm);

        // The failed message must be made visible again after the queue's own visibility
        // timeout (2s here) so the next poll re-receives it and the queue's RedrivePolicy
        // can move it to the DLQ — rather than staying in-flight for fn.timeout + 30s.
        verify(sqsService, timeout(2000)).changeMessageVisibility(
                esm.getQueueUrl(), "rh-m1", 2, "us-east-1");
        verify(sqsService, never()).deleteMessage(any(), any(), any());
    }

    @Test
    void failedInvocationFallsBackToDefaultVisibilityWhenQueueHasNone() {
        EventSourceMapping esm = esm();
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("throwfn");
        fn.setTimeout(10);
        when(functionStore.getForAccount("000000000000", "us-east-1", "throwfn"))
                .thenReturn(Optional.of(fn));

        Message msg = message("m1");
        when(sqsService.receiveMessage(eq(esm.getQueueUrl()), anyInt(), anyInt(), anyInt(), eq("us-east-1")))
                .thenReturn(List.of(msg));
        // Queue reports no VisibilityTimeout attribute.
        when(sqsService.getQueueAttributes(eq(esm.getQueueUrl()), any(), eq("us-east-1")))
                .thenReturn(Map.of());

        InvokeResult failure = new InvokeResult();
        failure.setFunctionError("Handled");
        when(executorService.invoke(eq(fn), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(failure);

        poller.pollAndInvoke(esm);

        // Falls back to the AWS default of 30s rather than 0 (which would spin a tight retry loop).
        verify(sqsService, timeout(2000)).changeMessageVisibility(
                esm.getQueueUrl(), "rh-m1", 30, "us-east-1");
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

    @Test
    void firstReceiveTimestampRemainsStableAcrossRetries() throws Exception {
        Instant firstReceived = Instant.parse("2026-01-15T10:31:00Z");
        Message msg = new Message();
        msg.setBody("retry-body");
        msg.setSentTimestamp(Instant.parse("2026-01-15T10:30:00Z"));
        msg.setFirstReceiveTimestamp(firstReceived);
        msg.setReceiveCount(3);

        EventSourceMapping esm = new EventSourceMapping();
        esm.setEventSourceArn("arn:aws:sqs:us-east-1:000000000000:test-queue");
        esm.setRegion("us-east-1");

        String firstCall = poller.buildSqsEvent(List.of(msg), esm);
        Thread.sleep(10);
        String secondCall = poller.buildSqsEvent(List.of(msg), esm);

        JsonNode firstTs = OBJECT_MAPPER.readTree(firstCall)
                .get("Records").get(0).get("attributes").get("ApproximateFirstReceiveTimestamp");
        JsonNode secondTs = OBJECT_MAPPER.readTree(secondCall)
                .get("Records").get(0).get("attributes").get("ApproximateFirstReceiveTimestamp");

        assertEquals(String.valueOf(firstReceived.toEpochMilli()), firstTs.asText());
        assertEquals(firstTs.asText(), secondTs.asText(),
                "ApproximateFirstReceiveTimestamp must not change between retries");
    }

    // ──────────────────────────── FilterCriteria ────────────────────────────

    private EventSourceMapping esmWithFilter(int batchSize, String... patterns) {
        EventSourceMapping esm = esm();
        esm.setBatchSize(batchSize);
        if (patterns.length > 0) {
            EventSourceMapping.FilterCriteria fc = new EventSourceMapping.FilterCriteria();
            List<EventSourceMapping.Filter> filters = new java.util.ArrayList<>();
            for (String p : patterns) {
                EventSourceMapping.Filter f = new EventSourceMapping.Filter();
                f.setPattern(p);
                filters.add(f);
            }
            fc.setFilters(filters);
            esm.setFilterCriteria(fc);
        }
        return esm;
    }

    private Message msgBody(String id, String body) {
        Message m = new Message();
        m.setMessageId(id);
        m.setReceiptHandle("rh-" + id);
        m.setBody(body);
        m.setSentTimestamp(Instant.now());
        m.setFirstReceiveTimestamp(Instant.now());
        return m;
    }

    private LambdaFunction stubThrowFn() {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("throwfn");
        fn.setTimeout(10);
        when(functionStore.getForAccount("000000000000", "us-east-1", "throwfn")).thenReturn(Optional.of(fn));
        return fn;
    }

    private void stubReceive(EventSourceMapping esm, List<Message> messages) {
        when(sqsService.receiveMessage(eq(esm.getQueueUrl()), anyInt(), anyInt(), anyInt(), eq("us-east-1")))
                .thenReturn(messages);
    }

    /**
     * Hard happens-after barrier: re-stub the fetch to return empty, then re-kick until a SECOND
     * receiveMessage is observed. {@code activePolls} serializes polls per ESM, so fetch #2 cannot begin until
     * poll #1's finally-block ran, so poll #1 is fully complete. The empty re-stub makes poll #2 a no-op, so
     * poll #1's exact delete/return counts are preserved for the assertions.
     */
    private void awaitPollCompleted(EventSourceMapping esm) {
        when(sqsService.receiveMessage(eq(esm.getQueueUrl()), anyInt(), anyInt(), anyInt(), eq("us-east-1")))
                .thenReturn(List.of());
        long deadline = System.currentTimeMillis() + 5000;
        while (true) {
            poller.pollAndInvoke(esm);
            try {
                verify(sqsService, atLeast(2))
                        .receiveMessage(eq(esm.getQueueUrl()), anyInt(), anyInt(), anyInt(), eq("us-east-1"));
                return;
            } catch (AssertionError retry) {
                if (System.currentTimeMillis() > deadline) {
                    throw retry;
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw retry;
                }
            }
        }
    }

    private static final String BODY_PATTERN = "{\"body\":{\"type\":[\"order\"]}}";

    @Test
    void filterDeletesNonMatchingAndInvokesMatchedOnly() {
        LambdaFunction fn = stubThrowFn();
        EventSourceMapping esm = esmWithFilter(10, BODY_PATTERN);
        Message m1 = msgBody("m1", "{\"type\":\"order\"}");
        Message m2 = msgBody("m2", "{\"type\":\"refund\"}");
        stubReceive(esm, List.of(m1, m2));
        when(executorService.invoke(eq(fn), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult());

        poller.pollAndInvoke(esm);

        // Non-matching m2 is deleted immediately (consumed, per AWS SQS filtering).
        verify(sqsService, timeout(2000)).deleteMessage(esm.getQueueUrl(), "rh-m2", "us-east-1");
        // Only m1 is delivered to the function.
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(executorService, timeout(2000)).invoke(eq(fn), payload.capture(), eq(InvocationType.RequestResponse));
        try {
            JsonNode records = OBJECT_MAPPER.readTree(payload.getValue()).get("Records");
            assertEquals(1, records.size());
            assertEquals("m1", records.get(0).get("messageId").asText());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // m1 deleted after successful invoke; nothing returned to the queue.
        verify(sqsService, timeout(2000)).deleteMessage(esm.getQueueUrl(), "rh-m1", "us-east-1");
        verify(sqsService, never()).changeMessageVisibility(any(), any(), anyInt(), any());
    }

    @Test
    void fullyFilteredBatchDeletesAllWithoutInvoking() {
        stubThrowFn();
        EventSourceMapping esm = esmWithFilter(10, BODY_PATTERN);
        Message m1 = msgBody("m1", "{\"type\":\"refund\"}");
        Message m2 = msgBody("m2", "{\"type\":\"chargeback\"}");
        stubReceive(esm, List.of(m1, m2));

        poller.pollAndInvoke(esm);

        verify(sqsService, timeout(2000)).deleteMessage(esm.getQueueUrl(), "rh-m1", "us-east-1");
        verify(sqsService, timeout(2000)).deleteMessage(esm.getQueueUrl(), "rh-m2", "us-east-1");
        awaitPollCompleted(esm);
        verify(executorService, never()).invoke(any(), any(byte[].class), any());
    }

    @Test
    void invokeErrorStillConsumesFilteredOutButReturnsMatched() {
        LambdaFunction fn = stubThrowFn();
        EventSourceMapping esm = esmWithFilter(10, BODY_PATTERN);
        Message m1 = msgBody("m1", "{\"type\":\"order\"}");   // matches
        Message m2 = msgBody("m2", "{\"type\":\"refund\"}");  // filtered out
        stubReceive(esm, List.of(m1, m2));
        when(sqsService.getQueueAttributes(eq(esm.getQueueUrl()), any(), eq("us-east-1")))
                .thenReturn(Map.of("VisibilityTimeout", "5"));
        InvokeResult err = new InvokeResult();
        err.setFunctionError("Handled");
        when(executorService.invoke(eq(fn), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(err);

        poller.pollAndInvoke(esm);

        // Filtered-out m2 is consumed (deleted) regardless of the invoke outcome.
        verify(sqsService, timeout(2000)).deleteMessage(esm.getQueueUrl(), "rh-m2", "us-east-1");
        // Matched m1 failed → returned to the queue (visibility reset), NOT deleted.
        verify(sqsService, timeout(2000)).changeMessageVisibility(esm.getQueueUrl(), "rh-m1", 5, "us-east-1");
        awaitPollCompleted(esm);
        verify(sqsService, never()).deleteMessage(esm.getQueueUrl(), "rh-m1", "us-east-1");
    }

    @Test
    void batchItemFailureIdOfFilteredMessageIsInert() {
        LambdaFunction fn = stubThrowFn();
        EventSourceMapping esm = esmWithFilter(10, BODY_PATTERN);
        esm.setFunctionResponseTypes(List.of("ReportBatchItemFailures"));
        Message m1 = msgBody("m1", "{\"type\":\"order\"}");   // matches, delivered
        Message m2 = msgBody("m2", "{\"type\":\"refund\"}");  // filtered out, deleted pre-invoke
        stubReceive(esm, List.of(m1, m2));
        // Function (defensively) reports the already-filtered id m2 as failed.
        InvokeResult result = new InvokeResult();
        result.setPayload("{\"batchItemFailures\":[{\"itemIdentifier\":\"m2\"}]}".getBytes());
        when(executorService.invoke(eq(fn), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(result);

        poller.pollAndInvoke(esm);

        // m1 (delivered, not reported failed) is deleted post-success; m2 was deleted once as filtered-out.
        verify(sqsService, timeout(2000)).deleteMessage(esm.getQueueUrl(), "rh-m1", "us-east-1");
        verify(sqsService, timeout(2000)).deleteMessage(esm.getQueueUrl(), "rh-m2", "us-east-1");
        awaitPollCompleted(esm);
        // The stale failure id does not cause a second delete or a visibility reset.
        verify(sqsService, never()).changeMessageVisibility(any(), any(), anyInt(), any());
    }

    @Test
    void nonJsonBodyDroppedByObjectPattern() {
        LambdaFunction fn = stubThrowFn();
        EventSourceMapping esm = esmWithFilter(10, BODY_PATTERN);
        Message m1 = msgBody("m1", "not json");                 // dropped by object pattern
        Message m2 = msgBody("m2", "{\"type\":\"order\"}");      // matches
        stubReceive(esm, List.of(m1, m2));
        when(executorService.invoke(eq(fn), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult());

        poller.pollAndInvoke(esm);

        verify(sqsService, timeout(2000)).deleteMessage(esm.getQueueUrl(), "rh-m1", "us-east-1");
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(executorService, timeout(2000)).invoke(eq(fn), payload.capture(), eq(InvocationType.RequestResponse));
        try {
            JsonNode records = OBJECT_MAPPER.readTree(payload.getValue()).get("Records");
            assertEquals(1, records.size());
            assertEquals("m2", records.get(0).get("messageId").asText());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
