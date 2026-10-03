package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
@TestProfile(HarLoggingIntegrationTest.HarProfile.class)
class HarLoggingIntegrationTest {

    private static final Path HAR_FILE = Path.of("target", "test-har", "har-logging-test.jsonl");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * The Query POST body has to reach {@code postData.text}. Reading it in the pre-matching
     * filter threw {@code BlockingNotAllowedException} on the Vert.x IO thread, which dropped
     * every request carrying a body from the log and left only bodyless ones, so a log holding
     * the GET but not the POST is the regression this guards.
     *
     * <p>The log is JSONL: one HAR-shaped entry object per line, not a single HAR document. The
     * test parses each line as its own entry.
     */
    @Test
    void queryPostBodyAndResponseAreRecorded() throws IOException {
        given()
        .when()
            .get("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .body("Action=ListQueues&Version=2012-11-05")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        List<JsonNode> entries = readEntries();
        assertFalse(entries.isEmpty(), "JSONL log has no entries");

        JsonNode postEntry = findEntry(entries, "POST", "Action=ListQueues");
        assertNotNull(postEntry, "log has no POST entry carrying the form-encoded body: " + entries);
        assertThat(postEntry.path("request").path("postData").path("mimeType").asText(),
                containsString("application/x-www-form-urlencoded"));
        assertThat(postEntry.path("request").path("postData").path("text").asText(),
                containsString("Version=2012-11-05"));
        assertThat(postEntry.path("request").path("bodySize").asInt(), is(not(0)));
        assertThat(postEntry.path("response").path("status").asInt(), equalTo(200));
        assertThat(postEntry.path("response").path("content").path("text").asText(),
                containsString("ListQueuesResponse"));

        JsonNode getEntry = findEntry(entries, "GET", null);
        assertNotNull(getEntry, "log has no bodyless GET entry");
        assertThat(getEntry.path("request").has("postData"), is(false));
    }

    /**
     * Finding #2: a signed request must have its replayable credentials redacted in the log, while
     * the non-secret access key id is still captured for attribution. Sends a SigV4-style
     * Authorization header and asserts the stored header value is redacted and callerAccessKeyId
     * holds the signing key.
     */
    @Test
    void signedRequestRedactsCredentialsAndRecordsAccessKey() throws IOException {
        // JSONL appends across requests (and across test runs if the file survives), so start from
        // a clean file to be sure findEntry matches this request's entry, not an earlier one.
        Files.deleteIfExists(HAR_FILE);

        given()
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=AKIAEXAMPLE/20260928/us-east-1/sqs/aws4_request, "
                            + "SignedHeaders=host;x-amz-date, Signature=deadbeef")
            .header("X-Amz-Security-Token", "FwoGZXIvYXdzEXAMPLETOKEN")
            .contentType("application/x-www-form-urlencoded")
            .body("Action=ListQueues&Version=2012-11-05")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        JsonNode entry = findEntry(readEntries(), "POST", "Action=ListQueues");
        assertNotNull(entry, "no POST entry captured for the signed request");

        assertThat(entry.path("callerAccessKeyId").asText(), equalTo("AKIAEXAMPLE"));
        assertThat(headerValue(entry, "Authorization"), equalTo("<floci: redacted>"));
        assertThat(headerValue(entry, "X-Amz-Security-Token"), equalTo("<floci: redacted>"));
        // A non-secret header is preserved, proving redaction is targeted, not blanket.
        assertThat(headerValue(entry, "Authorization"), not(containsString("deadbeef")));
    }

    private static String headerValue(JsonNode entry, String name) {
        for (JsonNode header : entry.path("request").path("headers")) {
            if (name.equalsIgnoreCase(header.path("name").asText())) {
                return header.path("value").asText();
            }
        }
        return null;
    }

    /** Each non-blank line is one JSON entry object; parsing per line validates the JSONL shape. */
    private List<JsonNode> readEntries() throws IOException {
        List<JsonNode> entries = new ArrayList<>();
        for (String line : Files.readAllLines(HAR_FILE)) {
            if (!line.isBlank()) {
                entries.add(objectMapper.readTree(line));
            }
        }
        return entries;
    }

    private static JsonNode findEntry(List<JsonNode> entries, String method, String bodyFragment) {
        for (JsonNode entry : entries) {
            JsonNode request = entry.path("request");
            if (!method.equals(request.path("method").asText())) {
                continue;
            }
            if (bodyFragment == null) {
                return entry;
            }
            if (request.path("postData").path("text").asText("").contains(bodyFragment)) {
                return entry;
            }
        }
        return null;
    }

    public static final class HarProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.chaos.enabled", "true",
                    "floci.chaos.har.enabled", "true",
                    "floci.chaos.har.file", HAR_FILE.toString());
        }
    }
}
