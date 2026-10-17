package io.github.hectorvent.floci.services.bedrockruntime;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.bedrockruntime.backend.ProxyBackend;
import io.github.hectorvent.floci.testutil.LogCapture;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(BedrockProxyCancellationIntegrationTest.Profile.class)
class BedrockProxyLoggingIntegrationTest {

    @TestHTTPResource
    URI base;

    @ParameterizedTest
    @ValueSource(strings = {"converse", "converse-stream"})
    void upstreamErrorIsLoggedWithoutResponseBody(String operation) throws IOException {
        String marker = "synthetic-private-upstream-content";
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 18939), 0);
        backend.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"error\":\"" + marker + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, body.length);
            try (exchange) {
                exchange.getResponseBody().write(body);
            }
        });
        backend.start();
        try {
            List<LogRecord> records = LogCapture.capture(ProxyBackend.class, () ->
                    given()
                            .contentType("application/json")
                            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request")
                            .body("{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\"synthetic\"}]}]}")
                    .when()
                            .post("/model/fixture/" + operation)
                    .then()
                            .statusCode(424)
                            .body("__type", equalTo("ModelErrorException"))
                            .body("message", containsString("HTTP 503"))
                            .body("message", containsString(marker)));
            SimpleFormatter formatter = new SimpleFormatter();
            assertTrue(records.stream().anyMatch(record -> record.getLevel().equals(Level.WARNING)
                    && formatter.format(record).contains("HTTP 503")), "The upstream status must remain diagnosable");
            assertFalse(records.stream().anyMatch(record -> formatter.format(record).contains(marker)),
                    "Operational logs must not include upstream response bodies");
        } finally {
            backend.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"converse", "converse-stream"})
    void errorBodyReadIsBoundedWithoutWaitingForEof(String operation) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 18939), 0);
        backend.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, 32768);
            try (exchange) {
                exchange.getResponseBody().write("x".repeat(8192).getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        backend.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/model/fixture/" + operation))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\"synthetic\"}]}]}"))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(424, response.statusCode());
            assertTrue(response.body().contains("ModelErrorException"));
            assertTrue(response.body().contains("HTTP 503"));
            assertTrue(response.body().length() < 1024, "Caller error descriptions must remain bounded");
        } finally {
            release.countDown();
            backend.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({"converse, 0", "converse, 1", "converse, 50",
            "converse-stream, 0", "converse-stream, 1", "converse-stream, 50"})
    void stalledErrorBodyHasDeadlineAndClosesUpstream(String operation, int lines) throws Exception {
        String marker = "synthetic-private-upstream-content";
        String errorPrefix = "HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain\r\nContent-Length: 32768\r\n\r\n"
                + (marker + "\n").repeat(lines);
        try (StallingUpstream upstream = new StallingUpstream(18939, errorPrefix);
             HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/model/fixture/" + operation))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\"synthetic\"}]}]}"))
                    .build();
            List<LogRecord> records = LogCapture.capture(ProxyBackend.class, () -> {
                HttpResponse<String> response = assertDoesNotThrow(
                        () -> client.send(request, HttpResponse.BodyHandlers.ofString()));
                assertEquals(424, response.statusCode());
                assertTrue(response.body().contains("ModelErrorException"));
                assertTrue(response.body().contains("HTTP 503"));
                assertTrue(response.body().length() < 1024);
                assertTrue(assertDoesNotThrow(() -> upstream.awaitClosed(1, TimeUnit.SECONDS)),
                        "The proxy must close the stalled upstream body");
            });
            SimpleFormatter formatter = new SimpleFormatter();
            assertFalse(records.stream().anyMatch(record -> formatter.format(record).contains(marker)));
            assertTrue(records.stream().anyMatch(record -> formatter.format(record).contains("HTTP 503")));
        }
    }
}
