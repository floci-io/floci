package io.github.hectorvent.floci.services.eventbridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.testing.MutableClock;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EventBridgeApiDestinationDeliveryIntegrationTest {

    @Inject
    MutableClock clock;

    private static final String EB_CT = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void oauthConnectionFetchesTokenAndMergesBodyParameters() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<String> tokenAuthorization = new AtomicReference<>();
        AtomicReference<String> tokenBody = new AtomicReference<>();
        AtomicReference<String> hookAuthorization = new AtomicReference<>();
        AtomicReference<String> hookBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            tokenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            tokenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, "{\"access_token\":\"tok-123\",\"token_type\":\"Bearer\"}");
        });
        server.createContext("/hook", exchange -> {
            try {
                hookAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                hookBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                reply(exchange, "{}");
            } finally {
                delivered.countDown();
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            String connectionArn = createConnection("oauth-conn", String.format("""
                    {
                      "OAuthParameters": {
                        "AuthorizationEndpoint": "http://127.0.0.1:%d/token",
                        "HttpMethod": "POST",
                        "ClientParameters": {"ClientID": "cid", "ClientSecret": "csecret"}
                      },
                      "InvocationHttpParameters": {
                        "BodyParameters": [{"Key": "tenant", "Value": "alpha", "IsValueSecret": false}]
                      }
                    }
                    """, port), "OAUTH_CLIENT_CREDENTIALS");
            String destinationArn = createDestination("oauth-dest", connectionArn,
                    "http://127.0.0.1:" + port + "/hook");
            routeEventsTo("oauth-rule", "oauth.test", destinationArn);

            putEvent("oauth.test");

            assertTrue(delivered.await(5, TimeUnit.SECONDS), "webhook should receive the delivery");
            assertEquals("Basic Y2lkOmNzZWNyZXQ=", tokenAuthorization.get());
            assertTrue(tokenBody.get().contains("grant_type=client_credentials"), tokenBody.get());
            assertEquals("Bearer tok-123", hookAuthorization.get());
            assertTrue(hookBody.get().contains("\"tenant\":\"alpha\""), hookBody.get());
            assertTrue(hookBody.get().contains("\"source\":\"oauth.test\""), hookBody.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deliveryIsDroppedWhenOauthTokenRequestFails() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.createContext("/hook", exchange -> {
            delivered.countDown();
            reply(exchange, "{}");
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            String connectionArn = createConnection("oauth-fail-conn", String.format("""
                    {
                      "OAuthParameters": {
                        "AuthorizationEndpoint": "http://127.0.0.1:%d/token",
                        "HttpMethod": "POST",
                        "ClientParameters": {"ClientID": "cid", "ClientSecret": "wrong"}
                      }
                    }
                    """, port), "OAUTH_CLIENT_CREDENTIALS");
            String destinationArn = createDestination("oauth-fail-dest", connectionArn,
                    "http://127.0.0.1:" + port + "/hook");
            routeEventsTo("oauth-fail-rule", "oauth.fail", destinationArn);

            putEvent("oauth.fail");

            assertFalse(delivered.await(2, TimeUnit.SECONDS),
                    "an event must not be sent unauthenticated when the token request fails");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aTargetCannotOverrideTheConnectionCredentials() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<List<String>> apiKeyValues = new AtomicReference<>();
        AtomicReference<List<String>> tenantValues = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            apiKeyValues.set(exchange.getRequestHeaders().get("X-Api-Key"));
            tenantValues.set(exchange.getRequestHeaders().get("x-tenant"));
            exchange.getRequestBody().readAllBytes();
            reply(exchange, "{}");
            delivered.countDown();
        });
        server.start();

        try {
            String connectionArn = createConnection("override-conn", """
                    {"ApiKeyAuthParameters": {"ApiKeyName": "X-Api-Key", "ApiKeyValue": "from-connection"}}
                    """, "API_KEY");
            String destinationArn = createDestination("override-dest", connectionArn,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
            given()
                    .contentType(EB_CT)
                    .header("X-Amz-Target", "AWSEvents.PutRule")
                    .body("{\"Name\": \"override-rule\", \"EventPattern\": \"{\\\"source\\\":[\\\"override.test\\\"]}\"}")
                    .when().post("/")
                    .then().statusCode(200);
            given()
                    .contentType(EB_CT)
                    .header("X-Amz-Target", "AWSEvents.PutTargets")
                    .body(String.format("""
                            {"Rule": "override-rule", "Targets": [{"Id": "t1", "Arn": "%s",
                              "HttpParameters": {"HeaderParameters": {"x-api-key": "from-target", "x-tenant": "alpha"}}}]}
                            """, destinationArn))
                    .when().post("/")
                    .then().statusCode(200);

            putEvent("override.test");

            assertTrue(delivered.await(5, TimeUnit.SECONDS), "webhook should receive the delivery");
            assertEquals(List.of("from-connection"), apiKeyValues.get());
            assertEquals(List.of("alpha"), tenantValues.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aServerErrorIsRetriedUntilTheEndpointSucceeds() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (requests.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            reply(exchange, "{}");
            delivered.countDown();
        });
        server.start();

        try {
            String connectionArn = createConnection("retry-conn", """
                    {"ApiKeyAuthParameters": {"ApiKeyName": "k", "ApiKeyValue": "v"}}
                    """, "API_KEY");
            String destinationArn = createDestination("retry-dest", connectionArn,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
            routeEventsTo("retry-rule", "retry.test", destinationArn);

            putEvent("retry.test");

            assertTrue(awaitAdvancingClock(delivered), "the failed delivery should be retried");
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aDestinationOverItsRateLimitIsThrottledAndRetriedLater() throws Exception {
        CountDownLatch delivered = new CountDownLatch(2);
        List<Long> secondsOfDelivery = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            secondsOfDelivery.add(Instant.now().getEpochSecond());
            reply(exchange, "{}");
            delivered.countDown();
        });
        server.start();

        try {
            String connectionArn = createConnection("rate-conn", """
                    {"ApiKeyAuthParameters": {"ApiKeyName": "k", "ApiKeyValue": "v"}}
                    """, "API_KEY");
            String destinationArn = given()
                    .contentType(EB_CT)
                    .header("X-Amz-Target", "AWSEvents.CreateApiDestination")
                    .body(String.format("""
                            {"Name": "rate-dest", "ConnectionArn": "%s", "HttpMethod": "POST",
                             "InvocationRateLimitPerSecond": 1, "InvocationEndpoint": "http://127.0.0.1:%d/hook"}
                            """, connectionArn, server.getAddress().getPort()))
                    .when().post("/")
                    .then().statusCode(200)
                    .extract().jsonPath().getString("ApiDestinationArn");
            routeEventsTo("rate-rule", "rate.test", destinationArn);

            given()
                    .contentType(EB_CT)
                    .header("X-Amz-Target", "AWSEvents.PutEvents")
                    .body("""
                            {"Entries": [
                              {"Source": "rate.test", "DetailType": "Test", "Detail": "{}"},
                              {"Source": "rate.test", "DetailType": "Test", "Detail": "{}"}
                            ]}
                            """)
                    .when().post("/")
                    .then().statusCode(200);

            assertTrue(awaitAdvancingClock(delivered), "both events should be delivered eventually");
            assertEquals(2, secondsOfDelivery.size());
            assertNotEquals(secondsOfDelivery.get(0), secondsOfDelivery.get(1),
                    "a limit of one request per second must spread the deliveries over different seconds");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void createApiDestinationRejectsNonHttpEndpoint() {
        String connectionArn = createConnection("scheme-conn", """
                {"ApiKeyAuthParameters": {"ApiKeyName": "k", "ApiKeyValue": "v"}}
                """, "API_KEY");

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.CreateApiDestination")
                .body(String.format("""
                        {
                          "Name": "scheme-dest",
                          "ConnectionArn": "%s",
                          "InvocationEndpoint": "file:///etc/passwd",
                          "HttpMethod": "POST"
                        }
                        """, connectionArn))
                .when().post("/")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void listApiDestinationsPaginatesWithLimitAndNextToken() {
        String connectionArn = createConnection("page-conn", """
                {"ApiKeyAuthParameters": {"ApiKeyName": "k", "ApiKeyValue": "v"}}
                """, "API_KEY");
        for (String name : new String[] {"page-c", "page-a", "page-b"}) {
            createDestination(name, connectionArn, "https://api.example.com/hook");
        }

        String token = given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.ListApiDestinations")
                .body("{\"NamePrefix\": \"page-\", \"Limit\": 2}")
                .when().post("/")
                .then().statusCode(200)
                .body("ApiDestinations.size()", equalTo(2))
                .body("ApiDestinations[0].Name", equalTo("page-a"))
                .body("ApiDestinations[1].Name", equalTo("page-b"))
                .body("NextToken", equalTo("page-b"))
                .extract().jsonPath().getString("NextToken");

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.ListApiDestinations")
                .body(String.format("{\"NamePrefix\": \"page-\", \"Limit\": 2, \"NextToken\": \"%s\"}", token))
                .when().post("/")
                .then().statusCode(200)
                .body("ApiDestinations.size()", equalTo(1))
                .body("ApiDestinations[0].Name", equalTo("page-c"))
                .body("NextToken", nullValue());
    }

    // The test profile freezes the clock, so TargetDispatcher's retry backoff only elapses when the test advances it
    private boolean awaitAdvancingClock(CountDownLatch latch) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (latch.await(200, TimeUnit.MILLISECONDS)) {
                return true;
            }
            clock.advance(Duration.ofSeconds(5));
        }
        return false;
    }

    private static void reply(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String createConnection(String name, String authParameters, String authorizationType) {
        return given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.CreateConnection")
                .body(String.format("""
                        {"Name": "%s", "AuthorizationType": "%s", "AuthParameters": %s}
                        """, name, authorizationType, authParameters))
                .when().post("/")
                .then().statusCode(200)
                .extract().jsonPath().getString("ConnectionArn");
    }

    private static String createDestination(String name, String connectionArn, String endpoint) {
        return given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.CreateApiDestination")
                .body(String.format("""
                        {"Name": "%s", "ConnectionArn": "%s", "InvocationEndpoint": "%s", "HttpMethod": "POST"}
                        """, name, connectionArn, endpoint))
                .when().post("/")
                .then().statusCode(200)
                .extract().jsonPath().getString("ApiDestinationArn");
    }

    private static void routeEventsTo(String rule, String source, String destinationArn) {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutRule")
                .body(String.format("{\"Name\": \"%s\", \"EventPattern\": \"{\\\"source\\\":[\\\"%s\\\"]}\"}",
                        rule, source))
                .when().post("/")
                .then().statusCode(200);
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .body(String.format("{\"Rule\": \"%s\", \"Targets\": [{\"Id\": \"t1\", \"Arn\": \"%s\"}]}",
                        rule, destinationArn))
                .when().post("/")
                .then().statusCode(200);
    }

    private static void putEvent(String source) {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutEvents")
                .body(String.format("""
                        {"Entries": [{"Source": "%s", "DetailType": "Test", "Detail": "{\\"k\\":\\"v\\"}"}]}
                        """, source))
                .when().post("/")
                .then().statusCode(200);
    }
}
