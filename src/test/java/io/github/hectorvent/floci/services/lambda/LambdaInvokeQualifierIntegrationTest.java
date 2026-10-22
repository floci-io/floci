package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invoke ignored the {@code Qualifier} query parameter, so {@code ?Qualifier=} ran {@code $LATEST}
 * and an alias's asynchronous invocation settings were never applied.
 */
@QuarkusTest
class LambdaInvokeQualifierIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    SqsService sqsService;

    private static String zipB64(String body) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("handler.py"));
            zos.write(("def handler(e, c):\n    return {'body': '" + body + "'}\n")
                    .getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static Matcher<Integer> anyOf200Or201() {
        return anyOf(equalTo(200), equalTo(201));
    }

    private static String createFunction(String body) throws Exception {
        String name = "inv-qual-" + Long.toString(System.nanoTime(), 36);
        given()
            .contentType("application/json")
            .body("""
                {"FunctionName": "%s", "Runtime": "python3.12",
                 "Role": "arn:aws:iam::000000000000:role/r", "Handler": "handler.handler",
                 "Code": {"ZipFile": "%s"}}
                """.formatted(name, zipB64(body)))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(anyOf200Or201());
        return name;
    }

    private static void publishVersion(String fn, String expectedVersion) {
        given().contentType("application/json").body("{}")
        .when().post("/2015-03-31/functions/" + fn + "/versions")
        .then().statusCode(201).body("Version", equalTo(expectedVersion));
    }

    @Test
    void aQualifierQueryParameterSelectsThePublishedVersion() throws Exception {
        String fn = createFunction("v1");
        publishVersion(fn, "1");

        given()
            .contentType("application/json")
            .body("{\"ZipFile\": \"%s\"}".formatted(zipB64("v2")))
        .when()
            .put("/2015-03-31/functions/" + fn + "/code")
        .then()
            .statusCode(200);

        given().header("X-Amz-Invocation-Type", "DryRun").body("{}")
        .when().post("/2015-03-31/functions/" + fn + "/invocations?Qualifier=1")
        .then().statusCode(204).header("X-Amz-Executed-Version", "1");

        given().header("X-Amz-Invocation-Type", "DryRun").body("{}")
        .when().post("/2015-03-31/functions/" + fn + "/invocations")
        .then().statusCode(204).header("X-Amz-Executed-Version", "$LATEST");
    }

    @Test
    void aQualifierQueryParameterResolvesAnAlias() throws Exception {
        String fn = createFunction("v1");
        publishVersion(fn, "1");

        given()
            .contentType("application/json")
            .body("{\"Name\": \"live\", \"FunctionVersion\": \"1\"}")
        .when()
            .post("/2015-03-31/functions/" + fn + "/aliases")
        .then()
            .statusCode(201);

        given().header("X-Amz-Invocation-Type", "DryRun").body("{}")
        .when().post("/2015-03-31/functions/" + fn + "/invocations?Qualifier=live")
        .then().statusCode(204).header("X-Amz-Executed-Version", "1");
    }

    @Test
    void aQualifierQueryParameterThatDisagreesWithTheNameIsRejected() throws Exception {
        String fn = createFunction("v1");
        publishVersion(fn, "1");

        given().header("X-Amz-Invocation-Type", "DryRun").body("{}")
        .when().post("/2015-03-31/functions/" + fn + ":1/invocations?Qualifier=2")
        .then().statusCode(400).body("__type", equalTo("InvalidParameterValueException"));

        given().header("X-Amz-Invocation-Type", "DryRun").body("{}")
        .when().post("/2015-03-31/functions/" + fn + ":1/invocations?Qualifier=1")
        .then().statusCode(204).header("X-Amz-Executed-Version", "1");
    }

    @Test
    void aQualifierQueryParameterThatDoesNotResolveIsNotFound() throws Exception {
        String fn = createFunction("v1");

        given().header("X-Amz-Invocation-Type", "DryRun").body("{}")
        .when().post("/2015-03-31/functions/" + fn + "/invocations?Qualifier=99")
        .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void aQualifierQueryParameterAppliesToAnUnqualifiedFunctionArn() throws Exception {
        String fn = createFunction("v1");
        publishVersion(fn, "1");
        String functionArn = given()
        .when().get("/2015-03-31/functions/" + fn + "/configuration")
        .then().statusCode(200).extract().path("FunctionArn");

        given().header("X-Amz-Invocation-Type", "DryRun").body("{}")
        .when().post("/2015-03-31/functions/{functionArn}/invocations?Qualifier=1", functionArn)
        .then().statusCode(204).header("X-Amz-Executed-Version", "1");
    }

    @Test
    void aZeroReservationThrottlesQualifiedSyncInvokesAndSendsEventsToOnFailureWithoutRetries() throws Exception {
        String fn = createFunction("v1");
        publishVersion(fn, "1");
        given()
            .contentType("application/json")
            .body("{\"Name\": \"live\", \"FunctionVersion\": \"1\"}")
        .when()
            .post("/2015-03-31/functions/" + fn + "/aliases")
        .then()
            .statusCode(201);
        String queueUrl = RequestScopes.callAs(ACCOUNT,
                () -> sqsService.createQueue(fn + "-failures", Map.of(), REGION)).getQueueUrl();
        String queueArn = RequestScopes.callAs(ACCOUNT,
                () -> sqsService.getQueueAttributes(queueUrl, List.of("QueueArn"), REGION).get("QueueArn"));
        given()
            .contentType("application/json")
            .body("{\"MaximumRetryAttempts\": 2, \"DestinationConfig\": {\"OnFailure\": {\"Destination\": \"%s\"}}}"
                    .formatted(queueArn))
        .when()
            .put("/2019-09-25/functions/" + fn + "/event-invoke-config?Qualifier=1")
        .then()
            .statusCode(200);
        given()
            .contentType("application/json")
            .body("{\"ReservedConcurrentExecutions\": 0}")
        .when()
            .put("/2017-10-31/functions/" + fn + "/concurrency")
        .then()
            .statusCode(200);

        String[] targets = {fn + "/invocations?Qualifier=1", fn + "/invocations?Qualifier=live",
                fn + ":1/invocations", fn + ":live/invocations"};
        for (String target : targets) {
            given().header("X-Amz-Invocation-Type", "RequestResponse").body("{}")
            .when().post("/2015-03-31/functions/" + target)
            .then().statusCode(429).body("__type", equalTo("TooManyRequestsException"));
        }
        for (String target : targets) {
            given().header("X-Amz-Invocation-Type", "Event").body("{}")
            .when().post("/2015-03-31/functions/" + target)
            .then().statusCode(202);
        }

        List<Message> records = awaitMessages(queueUrl, targets.length);
        for (Message message : records) {
            JsonNode record = MAPPER.readTree(message.getBody());
            assertEquals(0, record.path("requestContext").path("approximateInvokeCount").asInt(),
                    "a throttled event never ran: " + record);
            assertTrue(record.path("requestContext").path("functionArn").asText().endsWith(":1"),
                    "the record should name the invoked version: " + record);
            assertEquals(429, record.path("responseContext").path("statusCode").asInt());
            assertEquals("TooManyRequestsException", record.path("responsePayload").path("errorType").asText());
        }
        Thread.sleep(2500);
        assertTrue(receive(queueUrl).isEmpty(), "a zero reservation sends each event once, without retries");
    }

    @Test
    void anEmptyQualifierQueryParameterIsRejected() throws Exception {
        String fn = createFunction("v1");

        given().header("X-Amz-Invocation-Type", "DryRun").queryParam("Qualifier", "").body("{}")
        .when().post("/2015-03-31/functions/" + fn + "/invocations")
        .then().statusCode(400).body("__type", equalTo("ValidationException"));
    }

    @Test
    void aQualifierQueryParameterLongerThan128CharactersIsRejected() throws Exception {
        String fn = createFunction("v1");

        given().header("X-Amz-Invocation-Type", "DryRun").queryParam("Qualifier", "a".repeat(129)).body("{}")
        .when().post("/2015-03-31/functions/" + fn + "/invocations")
        .then().statusCode(400).body("__type", equalTo("ValidationException"));

        given().header("X-Amz-Invocation-Type", "DryRun").queryParam("Qualifier", "a".repeat(128)).body("{}")
        .when().post("/2015-03-31/functions/" + fn + "/invocations")
        .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void aQualifierQueryParameterWithAnInvalidCharacterIsRejected() throws Exception {
        String fn = createFunction("v1");

        given().header("X-Amz-Invocation-Type", "DryRun").queryParam("Qualifier", " ").body("{}")
        .when().post("/2015-03-31/functions/" + fn + "/invocations")
        .then().statusCode(400).body("__type", equalTo("ValidationException"));
    }

    private List<Message> receive(String queueUrl) {
        return RequestScopes.callAs(ACCOUNT, () -> sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION));
    }

    private List<Message> awaitMessages(String queueUrl, int count) throws Exception {
        List<Message> messages = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (messages.size() < count && System.nanoTime() < deadline) {
            messages.addAll(receive(queueUrl));
            Thread.sleep(100);
        }
        assertEquals(count, messages.size(), "OnFailure records received from " + queueUrl);
        return messages;
    }
}
