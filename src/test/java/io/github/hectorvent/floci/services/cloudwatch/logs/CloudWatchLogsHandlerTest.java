package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handler-level tests for ARN-based log group/stream resolution (issue #1164).
 *
 * <p>Verifies that CloudWatch Logs APIs accept either a name or an ARN via
 * {@code logGroupIdentifier} / {@code logStreamName}, mirroring real AWS behavior
 * so SDK clients that pass {@code --log-group-identifier} work against floci.
 */
class CloudWatchLogsHandlerTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String GROUP = "/aws/rds/instance/mypostgres-dsf/postgresql";
    private static final String STREAM = "postgresql.log.2026-06-04";
    private static final String GROUP_ARN =
            "arn:aws:logs:" + REGION + ":" + ACCOUNT + ":log-group:" + GROUP;
    private static final String GROUP_ARN_WILDCARD = GROUP_ARN + ":*";
    private static final String STREAM_ARN = GROUP_ARN + ":log-stream:" + STREAM;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CloudWatchLogsService service;
    private CloudWatchLogsHandler handler;

    @BeforeEach
    void setUp() {
        service = new CloudWatchLogsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                10_000,
                new RegionResolver(REGION, ACCOUNT)
        );
        handler = new CloudWatchLogsHandler(service, MAPPER);

        service.createLogGroup(GROUP, null, null, REGION);
        service.createLogStream(GROUP, STREAM, REGION);
    }

    // ──────────────────────────── DescribeLogStreams ────────────────────────────

    @Test
    void describeLogStreamsByLogGroupIdentifierArnResolvesToGroup() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupIdentifier", GROUP_ARN);

        Response response = handler.handle("DescribeLogStreams", request, REGION);

        assertEquals(200, response.getStatus());
        JsonNode streams = ((ObjectNode) response.getEntity()).path("logStreams");
        assertEquals(1, streams.size());
        assertEquals(STREAM, streams.get(0).path("logStreamName").asText());
    }

    @Test
    void describeLogStreamsByLogGroupIdentifierArnWithWildcardSuffix() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupIdentifier", GROUP_ARN_WILDCARD);

        Response response = handler.handle("DescribeLogStreams", request, REGION);

        assertEquals(200, response.getStatus());
        assertEquals(1, ((ObjectNode) response.getEntity()).path("logStreams").size());
    }

    @Test
    void describeLogStreamsByNameStillWorks() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupName", GROUP);

        Response response = handler.handle("DescribeLogStreams", request, REGION);

        assertEquals(200, response.getStatus());
        assertEquals(1, ((ObjectNode) response.getEntity()).path("logStreams").size());
    }

    @Test
    void describeLogStreamsResponseIncludesPerStreamArn() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupName", GROUP);

        Response response = handler.handle("DescribeLogStreams", request, REGION);

        JsonNode stream = ((ObjectNode) response.getEntity()).path("logStreams").get(0);
        assertEquals(STREAM_ARN, stream.path("arn").asText());
    }

    @Test
    void describeLogStreamsPrefersLogGroupNameWhenBothProvided() {
        // logGroupName is the canonical field — if both are present, name wins
        // so a mistyped identifier doesn't silently override the explicit name.
        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupName", GROUP);
        request.put("logGroupIdentifier", "arn:aws:logs:us-east-1:000000000000:log-group:/does/not/exist");

        Response response = handler.handle("DescribeLogStreams", request, REGION);

        assertEquals(200, response.getStatus());
        assertEquals(1, ((ObjectNode) response.getEntity()).path("logStreams").size());
    }

    // ──────────────────────────── PutLogEvents ────────────────────────────

    @Test
    void putLogEventsByLogGroupIdentifierArn() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupIdentifier", GROUP_ARN);
        request.put("logStreamName", STREAM);
        ArrayNode events = request.putArray("logEvents");
        ObjectNode event = events.addObject();
        event.put("timestamp", System.currentTimeMillis());
        event.put("message", "hello via ARN");

        Response response = handler.handle("PutLogEvents", request, REGION);

        assertEquals(200, response.getStatus());
        var stored = service.getLogEvents(GROUP, STREAM, null, null, 100, true, null, REGION);
        assertEquals(1, stored.events().size());
        assertEquals("hello via ARN", stored.events().getFirst().getMessage());
    }

    // ──────────────────────────── GetLogEvents ────────────────────────────

    @Test
    void getLogEventsByLogGroupIdentifierArnAndLogStreamArn() {
        long now = System.currentTimeMillis();
        service.putLogEvents(GROUP, STREAM,
                java.util.List.of(java.util.Map.of("timestamp", now, "message", "via arn")),
                REGION);

        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupIdentifier", GROUP_ARN);
        request.put("logStreamName", STREAM_ARN);

        Response response = handler.handle("GetLogEvents", request, REGION);

        assertEquals(200, response.getStatus());
        JsonNode events = ((ObjectNode) response.getEntity()).path("events");
        assertEquals(1, events.size());
        assertEquals("via arn", events.get(0).path("message").asText());
    }

    // ──────────────────────────── FilterLogEvents ────────────────────────────

    @Test
    void filterLogEventsByLogGroupIdentifierArnAndStreamArns() {
        long now = System.currentTimeMillis();
        service.putLogEvents(GROUP, STREAM, java.util.List.of(
                java.util.Map.of("timestamp", now, "message", "ERROR: kaboom"),
                java.util.Map.of("timestamp", now + 1, "message", "INFO: fine")
        ), REGION);

        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupIdentifier", GROUP_ARN);
        ArrayNode streamArns = request.putArray("logStreamNames");
        streamArns.add(STREAM_ARN);
        request.put("filterPattern", "ERROR");

        Response response = handler.handle("FilterLogEvents", request, REGION);

        assertEquals(200, response.getStatus());
        JsonNode events = ((ObjectNode) response.getEntity()).path("events");
        assertEquals(1, events.size());
        assertThat(events.get(0).path("message").asText(), containsString("ERROR"));
    }

    @Test
    void filterLogEventsByLogGroupIdentifierArnWithoutStreamFilter() {
        long now = System.currentTimeMillis();
        service.putLogEvents(GROUP, STREAM, java.util.List.of(
                java.util.Map.of("timestamp", now, "message", "a"),
                java.util.Map.of("timestamp", now + 1, "message", "b")
        ), REGION);

        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupIdentifier", GROUP_ARN);

        Response response = handler.handle("FilterLogEvents", request, REGION);

        assertEquals(200, response.getStatus());
        assertEquals(2, ((ObjectNode) response.getEntity()).path("events").size());
    }

    // ──────────────────────────── Resilience ────────────────────────────

    @Test
    void describeLogStreamsByPlainNamePassedAsIdentifier() {
        // Some callers pass the plain name through --log-group-identifier rather than
        // the ARN. The resolver must accept both forms.
        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupIdentifier", GROUP);

        Response response = handler.handle("DescribeLogStreams", request, REGION);

        assertEquals(200, response.getStatus());
        assertEquals(1, ((ObjectNode) response.getEntity()).path("logStreams").size());
    }

    @Test
    void perStreamArnDoesNotEndWithWildcard() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupName", GROUP);

        Response response = handler.handle("DescribeLogStreams", request, REGION);

        String arn = ((ObjectNode) response.getEntity())
                .path("logStreams").get(0).path("arn").asText();
        assertTrue(arn.contains(":log-stream:" + STREAM));
        assertThat(arn, not(containsString(":*")));
    }
}
