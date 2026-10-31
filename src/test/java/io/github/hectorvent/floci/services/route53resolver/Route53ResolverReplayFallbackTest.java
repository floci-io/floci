package io.github.hectorvent.floci.services.route53resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                new InMemoryStorage<>(), new InMemoryStorage<>(), endpointIpRequests, new InMemoryStorage<>(),
                objectMapper);
    }

    private JsonNode createRequest(String token, String ip) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("Name", "ab-fallback");
        request.put("Direction", "INBOUND");
        request.put("CreatorRequestId", token);
        request.putArray("SecurityGroupIds").add("sg-abc123");
        ArrayNode ipRequests = request.putArray("IpAddressRequests");
        ObjectNode ipRequest = ipRequests.addObject();
        ipRequest.put("SubnetId", "subnet-aaa");
        ipRequest.put("Ip", ip);
        ipRequest = ipRequests.addObject();
        ipRequest.put("SubnetId", "subnet-bbb");
        ipRequest.put("Ip", "10.0.0.6");
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
    void listingIpAddressesWithoutARecordFailsInsteadOfReturningEmpty() {
        // An empty list beside IpAddressCount=2 would be a silent wrong answer; the modeled
        // InternalServiceErrorException says the state is broken.
        ObjectNode created = service.createResolverEndpoint(createRequest("tok-list", "10.0.0.5"), REGION, ACCOUNT);
        String id = created.get("Id").asText();
        assertEquals(2, service.listResolverEndpointIpAddresses(id).size());

        endpointIpRequests.delete(id);
        AwsException broken = assertThrows(AwsException.class,
                () -> service.listResolverEndpointIpAddresses(id));
        assertEquals("InternalServiceErrorException", broken.jsonType());
        assertEquals(400, broken.getHttpStatus());
    }

    @Test
    void anIntactRecordStillReplaysAnIdenticalRequest() {
        // Guard against over-correcting: the ordinary path must stay idempotent.
        String token = "tok-fallback-intact";
        ObjectNode first = service.createResolverEndpoint(createRequest(token, "10.0.0.5"), REGION, ACCOUNT);
        ObjectNode second = service.createResolverEndpoint(createRequest(token, "10.0.0.5"), REGION, ACCOUNT);
        assertEquals(first.get("Id").asText(), second.get("Id").asText());
    }

    @Test
    void aRecordFromBeforeEndpointsHadInterfacesStillListsAndDeletes() {
        ObjectNode created = service.createResolverEndpoint(createRequest("tok-legacy", "10.0.0.5"), REGION, ACCOUNT);
        String id = created.get("Id").asText();
        ObjectNode legacy = objectMapper.createObjectNode();
        legacy.set("IpAddressRequests", endpointIpRequests.get(id).orElseThrow().get("IpAddressRequests"));
        endpointIpRequests.put(id, legacy);

        assertEquals(List.of("10.0.0.5", "10.0.0.6"), service.listResolverEndpointIpAddresses(id).stream()
                .map(a -> a.get("Ip").asText()).sorted().toList());
        assertEquals("DELETING", service.deleteResolverEndpoint(id).get("Status").asText());
        assertTrue(endpointIpRequests.get(id).isEmpty());
    }

    @Test
    void listingTakesTheAddressesAndTheirInterfacesFromOneRead() {
        // A delete landing between two reads must not leave ATTACHED addresses without their Ip.
        InMemoryStorage<String, ObjectNode> readOnce = new InMemoryStorage<>() {
            private boolean read;

            @Override
            public Optional<ObjectNode> get(String key) {
                if (read) {
                    return Optional.empty();
                }
                read = true;
                return super.get(key);
            }
        };
        Route53ResolverService racing = new Route53ResolverService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), readOnce, new InMemoryStorage<>(), objectMapper);
        ObjectNode created = racing.createResolverEndpoint(createRequest("tok-race", "10.0.0.5"), REGION, ACCOUNT);
        String id = created.get("Id").asText();
        ObjectNode record = objectMapper.createObjectNode();
        ArrayNode requests = record.putArray("IpAddressRequests");
        requests.addObject().put("SubnetId", "subnet-aaa");
        requests.addObject().put("SubnetId", "subnet-aaa");
        ArrayNode interfaces = record.putArray("NetworkInterfaces");
        interfaces.addObject().put("NetworkInterfaceId", "eni-1").put("Ip", "10.0.0.20");
        interfaces.addObject().put("NetworkInterfaceId", "eni-2").put("Ip", "10.0.0.21");
        readOnce.put(id, record);

        assertEquals(List.of("10.0.0.20", "10.0.0.21"), racing.listResolverEndpointIpAddresses(id).stream()
                .map(a -> a.path("Ip").asText("missing")).sorted().toList());
    }
}
