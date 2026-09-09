package io.github.hectorvent.floci.services.route53resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What {@code CreateResolverEndpoint} does when a stored endpoint has no recorded
 * {@code IpAddressRequests} — a state the public API cannot produce, so it needs the
 * hermetic constructor.
 *
 * <p>The recorded addresses are what a replayed {@code CreatorRequestId} is checked
 * against. Without them the service cannot tell a genuine retry from a different request
 * that happens to reuse the token, so it reports the conflict rather than returning an
 * endpoint that may not match what was asked for. Failing closed is the safe direction:
 * a spurious {@code ResourceExistsException} is loud and recoverable, while a wrong
 * success is silent.</p>
 */
class Route53ResolverReplayFallbackTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InMemoryStorage<String, ObjectNode> endpointIpRequests;
    private Route53ResolverService service;

    @BeforeEach
    void setUp() {
        endpointIpRequests = new InMemoryStorage<>();
        service = new Route53ResolverService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), endpointIpRequests, objectMapper);
    }

    private JsonNode createRequest(String token, String ip) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("Name", "ab-fallback");
        request.put("Direction", "INBOUND");
        request.put("CreatorRequestId", token);
        request.putArray("SecurityGroupIds").add("sg-abc123");
        ObjectNode ipRequest = request.putArray("IpAddressRequests").addObject();
        ipRequest.put("SubnetId", "subnet-aaa");
        ipRequest.put("Ip", ip);
        return request;
    }

    @Test
    void replayWithoutARecordedIpRequestIsReportedAsAConflict() {
        String token = "tok-fallback";
        ObjectNode created = service.createResolverEndpoint(createRequest(token, "10.0.0.5"), REGION, ACCOUNT);

        // Drop the record, leaving the endpoint behind: the shape an interrupted write, or a
        // store written by a build predating the record, would leave.
        endpointIpRequests.delete(created.get("Id").asText());

        // Same count, different address. Comparing IpAddressCount alone would call this an
        // equivalent replay and hand back the original endpoint.
        AwsException conflict = assertThrows(AwsException.class,
                () -> service.createResolverEndpoint(createRequest(token, "10.9.9.9"), REGION, ACCOUNT));
        assertEquals("ResourceExistsException", conflict.jsonType());
    }

    @Test
    void replayWithoutARecordedIpRequestConflictsEvenWhenTheRequestIsIdentical() {
        // The cost of failing closed, stated as a test rather than left implicit: with no
        // record there is nothing to compare against, so even a truly identical retry is
        // refused instead of being guessed at.
        String token = "tok-fallback-identical";
        ObjectNode created = service.createResolverEndpoint(createRequest(token, "10.0.0.5"), REGION, ACCOUNT);
        endpointIpRequests.delete(created.get("Id").asText());

        AwsException conflict = assertThrows(AwsException.class,
                () -> service.createResolverEndpoint(createRequest(token, "10.0.0.5"), REGION, ACCOUNT));
        assertEquals("ResourceExistsException", conflict.jsonType());
    }

    @Test
    void anIntactRecordStillReplaysAnIdenticalRequest() {
        // Guard against over-correcting: the ordinary path must stay idempotent.
        String token = "tok-fallback-intact";
        ObjectNode first = service.createResolverEndpoint(createRequest(token, "10.0.0.5"), REGION, ACCOUNT);
        ObjectNode second = service.createResolverEndpoint(createRequest(token, "10.0.0.5"), REGION, ACCOUNT);
        assertEquals(first.get("Id").asText(), second.get("Id").asText());
    }
}
