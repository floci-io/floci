package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.Connection;
import software.amazon.awssdk.services.eventbridge.model.ConnectionAuthorizationType;
import software.amazon.awssdk.services.eventbridge.model.ConnectionState;
import software.amazon.awssdk.services.eventbridge.model.EventBridgeException;
import software.amazon.awssdk.services.eventbridge.model.ListConnectionsRequest;
import software.amazon.awssdk.services.eventbridge.model.ListConnectionsResponse;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class EventBridgeListConnectionsPaginationIntegrationTest {

    private static final String ACCOUNT = "741665000001";
    private static final Region REGION = Region.EU_NORTH_1;
    private final String prefix = "connections-" + UUID.randomUUID() + "-";
    private final List<String> created = new ArrayList<>();
    private EventBridgeClient client;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void openClient() {
        client = client(ACCOUNT, REGION);
    }

    @AfterEach
    void cleanUp() {
        try {
            for (String name : created) {
                client.deleteConnection(request -> request.name(name));
            }
        } finally {
            client.close();
        }
    }

    @Test
    void sdkCallsWalkFilteredConnectionsInNameOrder() {
        create(prefix + "c");
        create(prefix + "a");
        create(prefix + "b");
        create("outside-" + UUID.randomUUID());
        ListConnectionsRequest request = ListConnectionsRequest.builder().namePrefix(prefix)
                .connectionState(ConnectionState.AUTHORIZED).limit(1).build();
        ListConnectionsResponse first = client.listConnections(request);
        assertEquals(1, first.connections().size());
        Connection connection = first.connections().getFirst();
        assertEquals(prefix + "a", connection.name());
        assertEquals(ConnectionAuthorizationType.API_KEY, connection.authorizationType());
        assertNotNull(connection.connectionArn());
        assertNotNull(connection.creationTime());
        assertNotNull(connection.lastModifiedTime());
        assertNotNull(first.nextToken());
        ListConnectionsResponse second = client.listConnections(request.toBuilder().nextToken(first.nextToken()).build());
        assertNotNull(second.nextToken());
        ListConnectionsResponse last = client.listConnections(request.toBuilder().nextToken(second.nextToken()).build());
        assertNull(last.nextToken());
        List<String> names = List.of(first, second, last).stream().flatMap(page -> page.connections().stream())
                .map(Connection::name).toList();
        assertEquals(List.of(prefix + "a", prefix + "b", prefix + "c"), names);
    }

    @Test
    void issuedCursorResumesAfterItsConnectionIsDeleted() {
        create(prefix + "a");
        create(prefix + "b");
        String token = client.listConnections(request -> request.namePrefix(prefix).limit(1)).nextToken();
        assertNotNull(token);
        client.deleteConnection(request -> request.name(prefix + "a"));
        created.remove(prefix + "a");
        ListConnectionsResponse last = client.listConnections(request -> request.namePrefix(prefix).limit(1).nextToken(token));
        assertEquals(List.of(prefix + "b"), last.connections().stream().map(Connection::name).toList());
        assertNull(last.nextToken());
    }

    @Test
    void omittedNullAndMaximumLimitUseABoundedPage() {
        for (int i = 0; i <= 100; i++) {
            create(prefix + "%03d".formatted(i));
        }
        ListConnectionsResponse first = client.listConnections(request -> request.namePrefix(prefix));
        assertEquals(100, first.connections().size());
        assertNotNull(first.nextToken());
        assertEquals(100, client.listConnections(request -> request.namePrefix(prefix).limit(100)).connections().size());
        assertEquals(100, ((List<?>) raw("{\"NamePrefix\":\"" + prefix + "\",\"Limit\":null}", 200).get("Connections")).size());
        ListConnectionsResponse last = client.listConnections(request -> request.namePrefix(prefix).nextToken(first.nextToken()));
        assertEquals(List.of(prefix + "100"), last.connections().stream().map(Connection::name).toList());
        assertNull(last.nextToken());
    }

    @Test
    void noMatchingStateReturnsAnEmptyFinalPage() {
        create(prefix + "a");
        create(prefix + "b");
        ListConnectionsResponse empty = client.listConnections(request -> request.namePrefix(prefix)
                .connectionState(ConnectionState.DEAUTHORIZED).limit(1));
        assertEquals(List.of(), empty.connections());
        assertNull(empty.nextToken());
    }

    @Test
    void issuedTokensCannotCrossAccountRegionPrefixOrState() {
        create(prefix + "a");
        create(prefix + "b");
        String token = client.listConnections(request -> request.namePrefix(prefix)
                .connectionState(ConnectionState.AUTHORIZED).limit(1)).nextToken();
        assertNotNull(token);
        assertInvalid(client, prefix + "other", ConnectionState.AUTHORIZED, token);
        assertInvalid(client, prefix, ConnectionState.DEAUTHORIZED, token);
        try (EventBridgeClient other = client("741665000002", REGION)) {
            assertInvalid(other, prefix, ConnectionState.AUTHORIZED, token);
        }
        try (EventBridgeClient other = client(ACCOUNT, Region.US_WEST_2)) {
            assertInvalid(other, prefix, ConnectionState.AUTHORIZED, token);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "0", "101", "2147483648", "4294967297"})
    void outOfRangeLimitsReturnValidationException(String limit) {
        assertEquals("ValidationException", raw("{\"Limit\":" + limit + "}", 400).get("__type"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "true", "\"1\"", "{}", "[]"})
    void malformedLimitsReturnSerializationException(String limit) {
        assertEquals("SerializationException", raw("{\"Limit\":" + limit + "}", 400).get("__type"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%%%%", "not-a-cursor", "d3Jvbmc6Y3Vyc29y"})
    void malformedOrWrongScopeCursorsReturnInvalidToken(String token) {
        assertEquals("InvalidToken", raw("{\"NextToken\":\"" + token + "\"}", 400).get("__type"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "true", "{}", "[]"})
    void nonStringTokensReturnSerializationException(String token) {
        assertEquals("SerializationException", raw("{\"NextToken\":" + token + "}", 400).get("__type"));
    }

    @Test
    void emptyAndOverlongTokensFailValidation() {
        for (String token : List.of("", "a".repeat(2049))) {
            assertEquals("ValidationException", raw("{\"NextToken\":\"" + token + "\"}", 400).get("__type"));
        }
    }

    private void assertInvalid(EventBridgeClient sdk, String namePrefix, ConnectionState state, String token) {
        EventBridgeException error = assertThrows(EventBridgeException.class, () -> sdk.listConnections(request -> request
                .namePrefix(namePrefix).connectionState(state).limit(1).nextToken(token)));
        assertEquals(400, error.statusCode());
        assertEquals("InvalidToken", error.awsErrorDetails().errorCode());
    }

    private void create(String name) {
        client.createConnection(request -> request.name(name).authorizationType(ConnectionAuthorizationType.API_KEY)
                .authParameters(auth -> auth.apiKeyAuthParameters(key -> key.apiKeyName("x-fixture-key").apiKeyValue("fixture"))));
        created.add(name);
    }

    private EventBridgeClient client(String account, Region region) {
        return EventBridgeClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"))).build();
    }

    private Map<String, Object> raw(String body, int status) {
        Map<String, Object> response = given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWSEvents.ListConnections")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/" + REGION.id() + "/events/aws4_request")
                .body(body).post("/").then().statusCode(status).extract().jsonPath().getMap("");
        if (status == 200) {
            for (Object item : (List<?>) response.get("Connections")) {
                assertFalse(((Map<?, ?>) item).containsKey("AuthParameters"));
            }
        }
        return response;
    }
}
