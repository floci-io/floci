package io.github.hectorvent.floci.services.eventbridgev2;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridgev2.EventBridgeV2Client;
import software.amazon.awssdk.services.eventbridgev2.model.BusState;
import software.amazon.awssdk.services.eventbridgev2.model.CreateEventBusResponse;
import software.amazon.awssdk.services.eventbridgev2.model.DescribeEventBusResponse;
import software.amazon.awssdk.services.eventbridgev2.model.EventBridgeV2Exception;
import software.amazon.awssdk.services.eventbridgev2.model.EventBusSummary;
import software.amazon.awssdk.services.eventbridgev2.model.IdempotentParameterMismatchException;
import software.amazon.awssdk.services.eventbridgev2.model.InvalidInputException;
import software.amazon.awssdk.services.eventbridgev2.model.ListEventBusesResponse;
import software.amazon.awssdk.services.eventbridgev2.model.ResourceAlreadyExistsException;
import software.amazon.awssdk.services.eventbridgev2.model.ResourceNotFoundException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EventBridgeV2IntegrationTest {

    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());

    @Test
    void sdkCreatesAnEventBusAndReadsItsResponse() {
        String name = "orders-" + suffix();
        Instant before = Instant.now().minusSeconds(1);
        try (EventBridgeV2Client client = client()) {
            CreateEventBusResponse response = client.createEventBus(request -> request.name(name)
                    .description("Order events")
                    .storageConfiguration(storage -> storage.retentionPeriodInDays(30))
                    .encryptionConfiguration(encryption -> encryption.kmsKeyIdentifier("alias/orders"))
                    .tags(Map.of("team", "orders")));

            assertTrue(response.eventBusArn().matches(
                    "arn:aws:events:us-east-1:000000000000:event-busv2/" + name + "/[a-z0-9]{25}"), response.eventBusArn());
            assertEquals(name, response.name());
            assertEquals("Order events", response.description());
            assertEquals(30, response.storageConfiguration().retentionPeriodInDays());
            assertEquals("alias/orders", response.encryptionConfiguration().kmsKeyIdentifier());
            assertEquals(BusState.ACTIVE, response.state());
            // The SDK decodes CreationTime only from a CBOR timestamp.
            assertTrue(!response.creationTime().isBefore(before)
                    && response.creationTime().isBefore(Instant.now().plus(Duration.ofSeconds(1))),
                    response.creationTime().toString());
        }
    }

    @Test
    void sdkRetryWithTheSameClientTokenReturnsTheSameBus() {
        String name = "retry-" + suffix();
        try (EventBridgeV2Client client = client()) {
            String arn = client.createEventBus(request -> request.name(name).clientToken("token-" + name)).eventBusArn();
            assertEquals(arn, client.createEventBus(request -> request.name(name).clientToken("token-" + name))
                    .eventBusArn());
            IdempotentParameterMismatchException mismatch = assertThrows(IdempotentParameterMismatchException.class,
                    () -> client.createEventBus(request -> request.name(name).clientToken("token-" + name)
                            .description("changed")));
            assertEquals(409, mismatch.statusCode());
        }
    }

    @Test
    void sdkCreatingAnExistingBusFailsWithResourceAlreadyExists() {
        String name = "dup-" + suffix();
        try (EventBridgeV2Client client = client()) {
            client.createEventBus(request -> request.name(name));
            ResourceAlreadyExistsException e = assertThrows(ResourceAlreadyExistsException.class,
                    () -> client.createEventBus(request -> request.name(name)));
            assertEquals(409, e.statusCode());
        }
    }

    @Test
    void sdkInvalidNameFailsWithInvalidInput() {
        try (EventBridgeV2Client client = client()) {
            InvalidInputException e = assertThrows(InvalidInputException.class,
                    () -> client.createEventBus(request -> request.name("not valid")));
            assertEquals(400, e.statusCode());
        }
    }

    @Test
    void sdkDescribesTheBusItCreated() {
        String name = "describe-" + suffix();
        try (EventBridgeV2Client client = client()) {
            CreateEventBusResponse created = client.createEventBus(request -> request.name(name)
                    .description("Order events")
                    .storageConfiguration(storage -> storage.retentionPeriodInDays(30))
                    .encryptionConfiguration(encryption -> encryption.kmsKeyIdentifier("alias/orders")));

            DescribeEventBusResponse described = client.describeEventBus(request -> request
                    .eventBusArn(created.eventBusArn()));
            assertEquals(created.eventBusArn(), described.eventBusArn());
            assertEquals(name, described.name());
            assertEquals("Order events", described.description());
            assertEquals(30, described.storageConfiguration().retentionPeriodInDays());
            assertEquals("alias/orders", described.encryptionConfiguration().kmsKeyIdentifier());
            assertEquals(BusState.ACTIVE, described.state());
            // The SDK decodes these only from CBOR timestamps.
            assertEquals(created.creationTime(), described.creationTime());
            assertNotNull(described.lastModifiedTime());

            String unknown = created.eventBusArn().substring(0, created.eventBusArn().lastIndexOf('/') + 1)
                    + "zzzzzzzzzzzzzzzzzzzzzzzzz";
            ResourceNotFoundException missing = assertThrows(ResourceNotFoundException.class,
                    () -> client.describeEventBus(request -> request.eventBusArn(unknown)));
            assertEquals(404, missing.statusCode());
        }
    }

    @Test
    void sdkListsTheBusesItCreatedPageByPage() {
        String prefix = "list-" + suffix() + "-";
        try (EventBridgeV2Client client = client()) {
            for (String name : List.of("c", "a", "b")) {
                client.createEventBus(request -> request.name(prefix + name).description("bus " + name));
            }

            ListEventBusesResponse first = client.listEventBuses(request -> request.namePrefix(prefix).maxResults(2));
            assertEquals(List.of(prefix + "a", prefix + "b"),
                    first.eventBuses().stream().map(EventBusSummary::name).toList());
            EventBusSummary a = first.eventBuses().getFirst();
            assertTrue(a.eventBusArn().startsWith("arn:aws:events:us-east-1:000000000000:event-busv2/" + prefix + "a/"),
                    a.eventBusArn());
            assertEquals("bus a", a.description());
            assertEquals(BusState.ACTIVE, a.state());
            assertEquals("000000000000", a.eventBusAccountId());
            // The SDK decodes these only from CBOR timestamps.
            assertNotNull(a.creationTime());
            assertEquals(a.creationTime(), a.lastModifiedTime());

            List<String> all = client.listEventBusesPaginator(request -> request.namePrefix(prefix).maxResults(1))
                    .eventBuses().stream().map(EventBusSummary::name).toList();
            assertEquals(List.of(prefix + "a", prefix + "b", prefix + "c"), all);
        }
    }

    @Test
    void sdkUnsupportedOperationFailsAsSuch() {
        try (EventBridgeV2Client client = client()) {
            EventBridgeV2Exception e = assertThrows(EventBridgeV2Exception.class, () -> client.listTagsForResource(
                    request -> request.resourceArn("arn:aws:events:us-east-1:000000000000:subscriber/x/"
                            + "abcdefghijklmnopqrstuvwxy")));
            assertEquals("UnsupportedOperation", e.awsErrorDetails().errorCode());
        }
    }

    /** A bus with no optional configuration answers without those members, and with its time as a CBOR tag 1. */
    @Test
    void createEventBusAnswersInSmithyRpcV2Cbor() throws Exception {
        String name = "wire-" + suffix();
        byte[] body = given().header("smithy-protocol", "rpc-v2-cbor").contentType("application/cbor")
                .accept("application/cbor").body(CBOR_MAPPER.writeValueAsBytes(Map.of("Name", name)))
                .post("/service/AWSEventsV2/operation/CreateEventBus")
                .then().statusCode(200).header("smithy-protocol", "rpc-v2-cbor").extract().asByteArray();
        JsonNode response = CBOR_MAPPER.readTree(body);
        assertEquals(name, response.path("Name").asText());
        assertEquals("ACTIVE", response.path("State").asText());
        assertNull(response.get("Description"));
        assertNull(response.get("StorageConfiguration"));
        assertEquals(1, tagOf(body, "CreationTime"), "CreationTime is not a CBOR timestamp (tag 1)");
    }

    /** The CBOR tag of a top-level member's value; -1 for none. */
    private static int tagOf(byte[] body, String member) throws Exception {
        try (CBORParser parser = (CBORParser) CBOR_MAPPER.getFactory().createParser(body)) {
            parser.nextToken();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                if (member.equals(field)) {
                    return parser.getCurrentTag();
                }
                parser.skipChildren();
            }
        }
        return -1;
    }

    private static EventBridgeV2Client client() {
        return EventBridgeV2Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
