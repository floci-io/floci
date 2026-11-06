package io.floci.aws.services.bedrockruntime;

import io.floci.aws.services.bedrockruntime.backend.ProxyBackend;
import io.floci.aws.testing.BedrockProxyBackendProfile;
import io.floci.aws.testutil.LogCapture;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

import static io.floci.aws.testing.BedrockProxyBackendProfile.PORT;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that an upstream error reaches the caller as ModelErrorException with a bounded
 * description, while the operational log keeps the status and leaves out the upstream body.
 *
 * <p>The backend URL and passthrough come from {@link BedrockProxyBackendProfile}, which this class
 * shares with the other proxy classes that talk to a stand-in. The {@code fixture} model id is not
 * in that profile's mapping, so it reaches the stand-in through passthrough.
 */
@QuarkusTest
@TestProfile(BedrockProxyBackendProfile.class)
class BedrockProxyLoggingIntegrationTest {

    @TestHTTPResource
    URI base;

    @ParameterizedTest
    @ValueSource(strings = {"converse", "converse-stream"})
    void upstreamErrorIsLoggedWithoutResponseBody(String operation) throws Exception {
        String marker = "synthetic-private-upstream-content";
        String body = "{\"error\":\"" + marker + "\"}";
        String completeError = "HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json\r\n"
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
        try (StallingUpstream upstream = new StallingUpstream(PORT, completeError)) {
            List<LogRecord> records = LogCapture.capture(ProxyBackend.class, () ->
                    given()
                            .contentType("application/json")
                            .header("Authorization", ConverseRequests.AUTHORIZATION)
                            .body(ConverseRequests.BODY)
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
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"converse", "converse-stream"})
    void errorBodyReadStopsAtTheCapWithoutWaitingForEof(String operation) throws Exception {
        String cappedError = "HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain\r\nContent-Length: 32768\r\n\r\n"
                + "x".repeat(8192);
        try (StallingUpstream upstream = new StallingUpstream(PORT, cappedError);
             HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(ConverseRequests.to(base, operation),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(424, response.statusCode());
            assertTrue(response.body().contains("ModelErrorException"));
            assertTrue(response.body().contains("HTTP 503"));
            // Only a read that ends at the cap carries the body; the deadline cut would return none of it.
            assertTrue(response.body().contains("x".repeat(512)), "The error read must end at the 8 KiB cap");
            assertTrue(response.body().length() < 1024, "Caller error descriptions must remain bounded");
        }
    }

    @ParameterizedTest
    @CsvSource({"converse, 0", "converse, 1", "converse, 50",
            "converse-stream, 0", "converse-stream, 1", "converse-stream, 50"})
    void stalledErrorBodyHasDeadlineAndClosesUpstream(String operation, int lines) throws Exception {
        String marker = "synthetic-private-upstream-content";
        String errorPrefix = "HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain\r\nContent-Length: 32768\r\n\r\n"
                + (marker + "\n").repeat(lines);
        try (StallingUpstream upstream = new StallingUpstream(PORT, errorPrefix);
             HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = ConverseRequests.to(base, operation);
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
