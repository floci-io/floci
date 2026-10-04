package io.github.hectorvent.floci.services.eventbridge;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventBridgeApiDestinationIntegrationTest {

    private static final String EB_CT = "application/x-amz-json-1.1";

    private static String connectionArn;
    private static String apiDestinationArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void setupConnection() {
        connectionArn = given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.CreateConnection")
                .body("""
                        {
                          "Name": "apidest-conn",
                          "Description": "Connection for ApiDestination test",
                          "AuthorizationType": "API_KEY",
                          "AuthParameters": {
                            "ApiKeyAuthParameters": {
                              "ApiKeyName": "x-api-key",
                              "ApiKeyValue": "test-key"
                            }
                          }
                        }
                        """)
                .when().post("/")
                .then().statusCode(200)
                .extract().jsonPath().getString("ConnectionArn");
    }

    @Test
    @Order(2)
    void createApiDestination() {
        apiDestinationArn = given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.CreateApiDestination")
                .body(String.format("""
                        {
                          "Name": "my-test-api-dest",
                          "Description": "Test Api Destination",
                          "ConnectionArn": "%s",
                          "InvocationEndpoint": "https://api.example.com/webhook",
                          "HttpMethod": "POST",
                          "InvocationRateLimitPerSecond": 10
                        }
                        """, connectionArn))
                .when().post("/")
                .then().statusCode(200)
                .body("ApiDestinationArn", startsWith("arn:aws:events:us-east-1:000000000000:api-destination/my-test-api-dest/"))
                .body("ApiDestinationState", equalTo("ACTIVE"))
                .body("CreationTime", notNullValue())
                .body("LastModifiedTime", notNullValue())
                .extract().jsonPath().getString("ApiDestinationArn");
    }

    @Test
    @Order(3)
    void createDuplicateApiDestinationFails() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.CreateApiDestination")
                .body(String.format("""
                        {
                          "Name": "my-test-api-dest",
                          "ConnectionArn": "%s",
                          "InvocationEndpoint": "https://api.example.com/webhook",
                          "HttpMethod": "POST"
                        }
                        """, connectionArn))
                .when().post("/")
                .then().statusCode(400)
                .body("__type", equalTo("ResourceAlreadyExistsException"));
    }

    @Test
    @Order(4)
    void describeApiDestination() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.DescribeApiDestination")
                .body("""
                        {
                          "Name": "my-test-api-dest"
                        }
                        """)
                .when().post("/")
                .then().statusCode(200)
                .body("ApiDestinationArn", equalTo(apiDestinationArn))
                .body("Name", equalTo("my-test-api-dest"))
                .body("Description", equalTo("Test Api Destination"))
                .body("ConnectionArn", equalTo(connectionArn))
                .body("InvocationEndpoint", equalTo("https://api.example.com/webhook"))
                .body("HttpMethod", equalTo("POST"))
                .body("InvocationRateLimitPerSecond", equalTo(10))
                .body("ApiDestinationState", equalTo("ACTIVE"))
                .body("CreationTime", notNullValue())
                .body("LastModifiedTime", notNullValue());
    }

    @Test
    @Order(5)
    void updateApiDestination() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.UpdateApiDestination")
                .body("""
                        {
                          "Name": "my-test-api-dest",
                          "Description": "Updated description",
                          "InvocationEndpoint": "https://api.example.com/v2/webhook",
                          "HttpMethod": "PUT",
                          "InvocationRateLimitPerSecond": 25
                        }
                        """)
                .when().post("/")
                .then().statusCode(200)
                .body("ApiDestinationArn", equalTo(apiDestinationArn))
                .body("ApiDestinationState", equalTo("ACTIVE"))
                .body("CreationTime", notNullValue())
                .body("LastModifiedTime", notNullValue());

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.DescribeApiDestination")
                .body("""
                        {
                          "Name": "my-test-api-dest"
                        }
                        """)
                .when().post("/")
                .then().statusCode(200)
                .body("Description", equalTo("Updated description"))
                .body("InvocationEndpoint", equalTo("https://api.example.com/v2/webhook"))
                .body("HttpMethod", equalTo("PUT"))
                .body("InvocationRateLimitPerSecond", equalTo(25));
    }

    @Test
    @Order(6)
    void listApiDestinations() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.ListApiDestinations")
                .body("""
                        {
                          "NamePrefix": "my-test"
                        }
                        """)
                .when().post("/")
                .then().statusCode(200)
                .body("ApiDestinations.size()", greaterThanOrEqualTo(1))
                .body("ApiDestinations[0].Name", equalTo("my-test-api-dest"))
                .body("ApiDestinations[0].ApiDestinationArn", equalTo(apiDestinationArn));
    }

    @Test
    @Order(7)
    void deleteApiDestination() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.DeleteApiDestination")
                .body("""
                        {
                          "Name": "my-test-api-dest"
                        }
                        """)
                .when().post("/")
                .then().statusCode(200);

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.DescribeApiDestination")
                .body("""
                        {
                          "Name": "my-test-api-dest"
                        }
                        """)
                .when().post("/")
                .then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(8)
    void eventDeliveryToApiDestination() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedUri = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedApiKey = new AtomicReference<>();
        AtomicReference<String> receivedConnHeader = new AtomicReference<>();
        AtomicReference<String> receivedTargetHeader = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            try {
                receivedUri.set(exchange.getRequestURI().toString());
                receivedApiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
                receivedConnHeader.set(exchange.getRequestHeaders().getFirst("x-conn-hdr"));
                receivedTargetHeader.set(exchange.getRequestHeaders().getFirst("x-target-hdr"));
                receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            } finally {
                latch.countDown();
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();

            // 1. Create connection with API Key and InvocationHttpParameters
            String connArn = given()
                    .contentType(EB_CT)
                    .header("X-Amz-Target", "AWSEvents.CreateConnection")
                    .body("""
                            {
                              "Name": "delivery-conn",
                              "AuthorizationType": "API_KEY",
                              "AuthParameters": {
                                "ApiKeyAuthParameters": {
                                  "ApiKeyName": "x-api-key",
                                  "ApiKeyValue": "my-secret-key"
                                },
                                "InvocationHttpParameters": {
                                  "HeaderParameters": [
                                    {"Key": "x-conn-hdr", "Value": "conn-val", "IsValueSecret": false}
                                  ]
                                }
                              }
                            }
                            """)
                    .when().post("/")
                    .then().statusCode(200)
                    .extract().jsonPath().getString("ConnectionArn");

            // 2. Create ApiDestination pointing to local server with wildcard path
            String destArn = given()
                    .contentType(EB_CT)
                    .header("X-Amz-Target", "AWSEvents.CreateApiDestination")
                    .body(String.format("""
                            {
                              "Name": "delivery-dest",
                              "ConnectionArn": "%s",
                              "InvocationEndpoint": "http://127.0.0.1:%d/webhook/*",
                              "HttpMethod": "POST"
                            }
                            """, connArn, port))
                    .when().post("/")
                    .then().statusCode(200)
                    .extract().jsonPath().getString("ApiDestinationArn");

            // 3. Put Rule
            given()
                    .contentType(EB_CT)
                    .header("X-Amz-Target", "AWSEvents.PutRule")
                    .body("""
                            {
                              "Name": "delivery-rule",
                              "EventPattern": "{\\"source\\":[\\"delivery.test\\"]}"
                            }
                            """)
                    .when().post("/")
                    .then().statusCode(200);

            // 4. Put Target with HttpParameters
            given()
                    .contentType(EB_CT)
                    .header("X-Amz-Target", "AWSEvents.PutTargets")
                    .body(String.format("""
                            {
                              "Rule": "delivery-rule",
                              "Targets": [
                                {
                                  "Id": "target-dest",
                                  "Arn": "%s",
                                  "HttpParameters": {
                                    "PathParameterValues": ["user-123"],
                                    "HeaderParameters": {
                                      "x-target-hdr": "target-val"
                                    },
                                    "QueryStringParameters": {
                                      "tenant": "alpha"
                                    }
                                  }
                                }
                              ]
                            }
                            """, destArn))
                    .when().post("/")
                    .then().statusCode(200);

            // 5. Put Event
            given()
                    .contentType(EB_CT)
                    .header("X-Amz-Target", "AWSEvents.PutEvents")
                    .body("""
                            {
                              "Entries": [
                                {
                                  "Source": "delivery.test",
                                  "DetailType": "OrderCreated",
                                  "Detail": "{\\"orderId\\":\\"12345\\"}"
                                }
                              ]
                            }
                            """)
                    .when().post("/")
                    .then().statusCode(200);

            boolean received = latch.await(5, TimeUnit.SECONDS);
            assertTrue(received, "HTTP server should receive request from EventBridge delivery");
            assertEquals("/webhook/user-123?tenant=alpha", receivedUri.get());
            assertEquals("my-secret-key", receivedApiKey.get());
            assertEquals("conn-val", receivedConnHeader.get());
            assertEquals("target-val", receivedTargetHeader.get());
            assertTrue(receivedBody.get().contains("\"orderId\":\"12345\""));
        } finally {
            server.stop(0);
        }
    }
}
