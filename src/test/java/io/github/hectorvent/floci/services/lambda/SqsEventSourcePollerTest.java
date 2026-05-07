package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqsEventSourcePollerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private SqsEventSourcePoller poller;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambdaConfig = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambdaConfig);
        when(lambdaConfig.pollIntervalMs()).thenReturn(1000L);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        poller = new SqsEventSourcePoller(
                mock(Vertx.class),
                mock(SqsService.class),
                mock(LambdaExecutorService.class),
                mock(LambdaFunctionStore.class),
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
}
