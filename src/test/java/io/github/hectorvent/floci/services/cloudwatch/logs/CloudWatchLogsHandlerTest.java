package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void describeLogStreamsHonorsOrderByDescendingLimitAndReturnsNextToken() {
        service.createLogStream(GROUP, "another-stream", REGION);
        service.putLogEvents(GROUP, STREAM,
                java.util.List.of(java.util.Map.of("timestamp", 2000L, "message", "new")), REGION);
        service.putLogEvents(GROUP, "another-stream",
                java.util.List.of(java.util.Map.of("timestamp", 1000L, "message", "old")), REGION);
        ObjectNode request = MAPPER.createObjectNode()
                .put("logGroupName", GROUP)
                .put("orderBy", "LastEventTime")
                .put("descending", true)
                .put("limit", 1);

        Response response = handler.handle("DescribeLogStreams", request, REGION);

        assertEquals(200, response.getStatus());
        ObjectNode entity = (ObjectNode) response.getEntity();
        JsonNode streams = entity.path("logStreams");
        assertEquals(1, streams.size());
        assertEquals(STREAM, streams.get(0).path("logStreamName").asText());
        assertTrue(entity.has("nextToken"));

        ObjectNode nextRequest = request.deepCopy().put("nextToken", entity.path("nextToken").asText());
        ObjectNode nextEntity = (ObjectNode) handler.handle("DescribeLogStreams", nextRequest, REGION).getEntity();
        assertEquals("another-stream", nextEntity.path("logStreams").get(0).path("logStreamName").asText());
        assertFalse(nextEntity.has("nextToken"));
    }

    // ──────────────────────────── GetDataProtectionPolicy ────────────────────────────

    @Test
    void getDataProtectionPolicyReturnsEmptyPolicyWith200() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupIdentifier", GROUP);

        Response response = handler.handle("GetDataProtectionPolicy", request, REGION);

        assertEquals(200, response.getStatus());
        JsonNode body = (JsonNode) response.getEntity();
        assertEquals(GROUP, body.path("logGroupIdentifier").asText());
        assertTrue(body.path("policyDocument").isMissingNode());
    }

    @Test
    void getDataProtectionPolicyByNameAlsoSucceeds() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupName", GROUP);

        Response response = handler.handle("GetDataProtectionPolicy", request, REGION);

        assertEquals(200, response.getStatus());
        assertEquals(GROUP, ((JsonNode) response.getEntity()).path("logGroupIdentifier").asText());
    }

    @Test
    void createLogGroupWithDeletionProtectionIsReturnedByDescribeLogGroups() {
        String protectedGroup = GROUP + "-created-protected";
        ObjectNode create = MAPPER.createObjectNode();
        create.put("logGroupName", protectedGroup);
        create.put("deletionProtectionEnabled", true);

        handler.handle("CreateLogGroup", create, REGION);

        ObjectNode describe = MAPPER.createObjectNode();
        describe.put("logGroupNamePrefix", protectedGroup);
        JsonNode group = ((JsonNode) handler.handle("DescribeLogGroups", describe, REGION).getEntity())
                .path("logGroups").get(0);
        assertEquals(protectedGroup, group.path("logGroupName").asText());
        assertTrue(group.path("deletionProtectionEnabled").asBoolean());
    }

    @Test
    void putLogGroupDeletionProtectionPersistsByName() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupIdentifier", GROUP);
        request.put("deletionProtectionEnabled", true);

        Response response = handler.handle("PutLogGroupDeletionProtection", request, REGION);

        assertEquals(200, response.getStatus());
        assertTrue(service.describeLogGroups(GROUP, REGION).getFirst().isDeletionProtectionEnabled());
    }

    @Test
    void putLogGroupDeletionProtectionAcceptsArnIdentifier() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupIdentifier", GROUP_ARN);
        request.put("deletionProtectionEnabled", true);

        handler.handle("PutLogGroupDeletionProtection", request, REGION);

        assertTrue(service.describeLogGroups(GROUP, REGION).getFirst().isDeletionProtectionEnabled());
    }

    @Test
    void putLogGroupDeletionProtectionRequiresIdentifier() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("deletionProtectionEnabled", true);

        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("PutLogGroupDeletionProtection", request, REGION));

        assertEquals("InvalidParameterException", error.getErrorCode());
    }

    @Test
    void putLogGroupDeletionProtectionRequiresBoolean() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupIdentifier", GROUP);

        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("PutLogGroupDeletionProtection", request, REGION));

        assertEquals("InvalidParameterException", error.getErrorCode());
    }

    @Test
    void putLogGroupDeletionProtectionRequiresExistingGroup() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupIdentifier", "/missing");
        request.put("deletionProtectionEnabled", true);

        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("PutLogGroupDeletionProtection", request, REGION));

        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }

    @Test
    void protectedLogGroupCannotBeDeletedUntilProtectionIsDisabled() {
        ObjectNode protection = MAPPER.createObjectNode();
        protection.put("logGroupIdentifier", GROUP);
        protection.put("deletionProtectionEnabled", true);
        handler.handle("PutLogGroupDeletionProtection", protection, REGION);

        ObjectNode deletion = MAPPER.createObjectNode();
        deletion.put("logGroupName", GROUP);
        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle("DeleteLogGroup", deletion, REGION));
        assertEquals("ValidationException", error.getErrorCode());
        assertThat(error.getMessage(), containsString("Disable deletion protection"));

        protection.put("deletionProtectionEnabled", false);
        handler.handle("PutLogGroupDeletionProtection", protection, REGION);
        Response response = handler.handle("DeleteLogGroup", deletion, REGION);

        assertEquals(200, response.getStatus());
        assertFalse(service.logGroupExists(GROUP, REGION));
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
        assertEquals(STREAM, events.get(0).path("logStreamName").asText());
    }

    @Test
    void filterLogEventsAttributesEachMatchToItsStream() {
        // FilterLogEvents spans streams, so a caller can only attribute a hit if the response
        // says which stream emitted it.
        String otherStream = "postgresql.log.2026-06-05";
        service.createLogStream(GROUP, otherStream, REGION);

        long now = System.currentTimeMillis();
        service.putLogEvents(GROUP, STREAM, java.util.List.of(
                java.util.Map.of("timestamp", now, "message", "ERROR: from first")
        ), REGION);
        service.putLogEvents(GROUP, otherStream, java.util.List.of(
                java.util.Map.of("timestamp", now + 1, "message", "ERROR: from second")
        ), REGION);

        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupName", GROUP);
        request.put("filterPattern", "ERROR");

        Response response = handler.handle("FilterLogEvents", request, REGION);

        assertEquals(200, response.getStatus());
        JsonNode events = ((ObjectNode) response.getEntity()).path("events");
        assertEquals(2, events.size());
        assertEquals(STREAM, events.get(0).path("logStreamName").asText());
        assertEquals(otherStream, events.get(1).path("logStreamName").asText());
    }

    @Test
    void getLogEventsOmitsLogStreamName() {
        // OutputLogEvent has no logStreamName in real AWS: the caller named the stream in the
        // request, so echoing it back would be a shape floci invents.
        long now = System.currentTimeMillis();
        service.putLogEvents(GROUP, STREAM, java.util.List.of(
                java.util.Map.of("timestamp", now, "message", "only one stream here")
        ), REGION);

        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupName", GROUP);
        request.put("logStreamName", STREAM);

        Response response = handler.handle("GetLogEvents", request, REGION);

        assertEquals(200, response.getStatus());
        JsonNode events = ((ObjectNode) response.getEntity()).path("events");
        assertEquals(1, events.size());
        assertTrue(events.get(0).path("logStreamName").isMissingNode());
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

    @Test
    void filterLogEventsPagesForwardWithTheReturnedToken() {
        // The wire-level version of the pagination contract: the token comes back on the
        // response, goes out on the next request, and the second page carries the matches the
        // first one capped off.
        long now = System.currentTimeMillis();
        service.putLogEvents(GROUP, STREAM, java.util.List.of(
                java.util.Map.of("timestamp", now, "message", "msg-0"),
                java.util.Map.of("timestamp", now + 1, "message", "msg-1"),
                java.util.Map.of("timestamp", now + 2, "message", "msg-2"),
                java.util.Map.of("timestamp", now + 3, "message", "msg-3"),
                java.util.Map.of("timestamp", now + 4, "message", "msg-4")
        ), REGION);

        ObjectNode firstRequest = MAPPER.createObjectNode();
        firstRequest.put("logGroupName", GROUP);
        firstRequest.put("limit", 3);

        Response firstResponse = handler.handle("FilterLogEvents", firstRequest, REGION);
        assertEquals(200, firstResponse.getStatus());
        ObjectNode firstBody = (ObjectNode) firstResponse.getEntity();
        assertEquals(3, firstBody.path("events").size());
        assertEquals("msg-0", firstBody.path("events").get(0).path("message").asText());
        String nextToken = firstBody.path("nextToken").asText(null);
        assertNotNull(nextToken, "a capped page must advertise the rest");

        ObjectNode secondRequest = MAPPER.createObjectNode();
        secondRequest.put("logGroupName", GROUP);
        secondRequest.put("limit", 3);
        secondRequest.put("nextToken", nextToken);

        Response secondResponse = handler.handle("FilterLogEvents", secondRequest, REGION);
        assertEquals(200, secondResponse.getStatus());
        ObjectNode secondBody = (ObjectNode) secondResponse.getEntity();
        assertEquals(2, secondBody.path("events").size());
        assertEquals("msg-3", secondBody.path("events").get(0).path("message").asText());
        assertEquals("msg-4", secondBody.path("events").get(1).path("message").asText());
        assertTrue(secondBody.path("nextToken").isMissingNode(),
                "an absent nextToken is how FilterLogEvents says pagination is finished");
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

    // ──────────────────────────── StartQuery selector validation ────────────────────────────

    @Test
    void startQueryWithMultipleSelectorTypesThrowsInvalidParameter() {
        // AWS requires exactly one of logGroupName / logGroupNames / logGroupIdentifiers.
        // Supplying two selector types (here: logGroupName + logGroupIdentifiers) must be rejected
        // with InvalidParameterException rather than silently querying the union of both.
        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupName", GROUP);
        request.putArray("logGroupIdentifiers").add(GROUP_ARN);
        request.put("startTime", 0L);
        request.put("endTime", 1L);
        request.put("queryString", "fields @message");

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("StartQuery", request, REGION));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    @Test
    void startQueryWithBlankSelectorAlongsideRealOneIsRejected() {
        // A serialized-but-blank logGroupName next to a real logGroupNames is still two selector fields,
        // which AWS rejects — the guard must count serialized fields, not treat the blank one as absent.
        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupName", "");
        request.putArray("logGroupNames").add(GROUP);
        request.put("startTime", 0L);
        request.put("endTime", 1L);
        request.put("queryString", "fields @message");

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("StartQuery", request, REGION));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }
}
