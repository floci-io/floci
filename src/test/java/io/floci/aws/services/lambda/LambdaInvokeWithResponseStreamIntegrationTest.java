package io.floci.aws.services.lambda;

import io.floci.aws.testing.EventStreamMessages;
import io.floci.aws.testing.EventStreamMessages.Message;
import io.floci.aws.testing.LambdaCodeFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LambdaInvokeWithResponseStreamIntegrationTest {

    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String STREAMING = "invoke-stream-" + SUFFIX;
    private static final String PLAIN = "invoke-plain-" + SUFFIX;
    private static final String EVENT_STREAM = "application/vnd.amazon.eventstream";

    private static boolean created;

    @BeforeEach
    void createFunctions() {
        if (created) {
            return;
        }
        created = true;
        create(STREAMING, """
                exports.handler = awslambda.streamifyResponse(async (event, responseStream) => {
                  if (event.contentType) responseStream.setContentType(event.contentType);
                  if (event.mode === 'log') console.log('streamed log line');
                  if (event.mode === 'throwBefore') throw new Error('boom before');
                  if (event.mode === 'exit') {
                    await new Promise(() => responseStream.write('hello ', () => process.exit(3)));
                  }
                  responseStream.write('hello ');
                  if (event.mode === 'throwAfter') throw new Error('boom after');
                  responseStream.write('world');
                  responseStream.end();
                });
                """);
        create(PLAIN, """
                exports.handler = async (event) => {
                  if (event.fail) throw new Error('boom');
                  return {ok: true};
                };
                """);
    }

    @Test
    void streamingHandlerSendsItsChunksThenInvokeComplete() {
        Response response = invoke(STREAMING, "{}", "");

        response.then().statusCode(200)
                .contentType(EVENT_STREAM)
                .header("X-Amz-Executed-Version", "$LATEST")
                .header("x-amzn-Remapped-Content-Type", "application/octet-stream");
        List<Message> messages = EventStreamMessages.decode(response.asByteArray());
        assertEquals("hello world", chunks(messages));
        assertEquals(1, messages.stream().filter(m -> "InvokeComplete".equals(m.type())).count());
        Message complete = messages.get(messages.size() - 1);
        assertEquals("InvokeComplete", complete.type());
        assertEquals("{}", complete.text());
        assertEquals(List.of(":event-type", ":content-type", ":message-type"),
                List.copyOf(complete.headers().keySet()));
        assertEquals("application/json", complete.headers().get(":content-type"));
        assertEquals("event", complete.headers().get(":message-type"));
        assertEquals("application/octet-stream", messages.get(0).headers().get(":content-type"));
    }

    @Test
    void contentTypeTheHandlerSetsIsReported() {
        Response response = invoke(STREAMING, "{\"contentType\":\"text/plain\"}", "");

        response.then().statusCode(200).header("x-amzn-Remapped-Content-Type", "text/plain");
        assertEquals("hello world", chunks(EventStreamMessages.decode(response.asByteArray())));
    }

    @Test
    void plainHandlerSendsItsResultAsOneChunk() {
        Response response = invoke(PLAIN, "{}", "");

        response.then().statusCode(200).header("x-amzn-Remapped-Content-Type", "application/json");
        List<Message> messages = EventStreamMessages.decode(response.asByteArray());
        assertEquals(2, messages.size());
        assertEquals("PayloadChunk", messages.get(0).type());
        assertEquals("{\"ok\":true}", messages.get(0).text());
        assertEquals("{}", messages.get(1).text());
    }

    @Test
    void plainHandlerThatThrowsSendsTheErrorThenUnhandled() {
        List<Message> messages = EventStreamMessages.decode(invoke(PLAIN, "{\"fail\":true}", "").asByteArray());

        assertEquals(2, messages.size());
        assertEquals("PayloadChunk", messages.get(0).type());
        assertTrue(messages.get(0).text().contains("boom"), messages.get(0).text());
        assertEquals("{\"ErrorCode\":\"Unhandled\",\"ErrorDetails\":\"\"}", messages.get(1).text());
    }

    @Test
    void errorBeforeAnyWriteIsReportedInInvokeComplete() {
        List<Message> messages = EventStreamMessages.decode(invoke(STREAMING, "{\"mode\":\"throwBefore\"}", "").asByteArray());

        assertEquals(1, messages.size());
        assertErrorComplete(messages.get(0), "boom before");
    }

    @Test
    void errorAfterAWriteKeepsTheChunkAndIsReported() {
        List<Message> messages = EventStreamMessages.decode(invoke(STREAMING, "{\"mode\":\"throwAfter\"}", "").asByteArray());

        assertEquals(2, messages.size());
        assertEquals("hello ", messages.get(0).text());
        assertErrorComplete(messages.get(1), "boom after");
    }

    @Test
    void runtimeExitInTheMiddleOfTheStreamIsATruncatedResponse() {
        List<Message> messages = EventStreamMessages.decode(invoke(STREAMING, "{\"mode\":\"exit\"}", "").asByteArray());

        assertEquals(2, messages.size());
        assertEquals("hello ", messages.get(0).text());
        assertEquals("{\"ErrorCode\":\"Runtime.TruncatedResponse\"}", messages.get(1).text());
    }

    private static void assertErrorComplete(Message complete, String message) {
        assertEquals("InvokeComplete", complete.type());
        assertTrue(complete.text().startsWith("{\"ErrorCode\":\"Error\",\"ErrorDetails\":\""), complete.text());
        assertTrue(complete.text().contains(message), complete.text());
    }

    @Test
    void logTailSendsOnlyLogResultInInvokeComplete() {
        Response response = given().header("X-Amz-Log-Type", "Tail").body("{\"mode\":\"log\"}")
                .when().post(path(STREAMING, ""));

        response.then().statusCode(200);
        List<Message> messages = EventStreamMessages.decode(response.asByteArray());
        Message complete = messages.get(messages.size() - 1);
        assertTrue(complete.text().startsWith("{\"LogResult\":\""), complete.text());
        String logs = new String(Base64.getDecoder().decode(complete.text().substring(14, complete.text().length() - 2)));
        assertTrue(logs.contains("streamed log line"), logs);
    }

    @Test
    void dryRunStillStreams() {
        Response response = given().header("X-Amz-Invocation-Type", "DryRun").body("{}")
                .when().post(path(STREAMING, ""));

        response.then().statusCode(200);
        assertEquals("hello world", chunks(EventStreamMessages.decode(response.asByteArray())));
    }

    @Test
    void qualifierSelectsAPublishedVersion() {
        given().contentType("application/json").body("{}")
                .when().post("/2015-03-31/functions/" + STREAMING + "/versions")
                .then().statusCode(201).body("Version", equalTo("1"));

        invoke(STREAMING, "{}", "?Qualifier=1").then().statusCode(200).header("X-Amz-Executed-Version", "1");
    }

    @Test
    void bodyOverTheLimitIsRefused() {
        byte[] body = new byte[6291457];
        Arrays.fill(body, (byte) 'x');

        given().body(body).when().post(path(STREAMING, ""))
                .then().statusCode(413)
                .body("__type", equalTo("RequestEntityTooLargeException"))
                .body("message", equalTo("6291457 byte payload is too large for the RequestResponse invocation type"
                        + " (limit 6291456 bytes)"));
    }

    @Test
    void invalidArgumentsAreAllReported() {
        given().header("X-Amz-Log-Type", "Bogus").header("X-Amz-Invocation-Type", "Bogus").body("{}")
                .when().post(path(STREAMING, "?Qualifier=a b"))
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("3 validation errors detected: "
                        + "Value 'Bogus' at 'logType' failed to satisfy constraint: "
                        + "Member must satisfy enum value set: [Tail, None]; "
                        + "Value 'Bogus' at 'invocationType' failed to satisfy constraint: "
                        + "Member must satisfy enum value set: [DryRun, RequestResponse, Event]; "
                        + "Value 'a b' at 'qualifier' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: \\$(LATEST(\\.PUBLISHED)?)|[a-zA-Z0-9-_$]+"));
    }

    @Test
    void emptyQualifierBreaksThePatternAndTheLength() {
        given().body("{}").when().post(path(STREAMING, "?Qualifier="))
                .then().statusCode(400)
                .body("message", equalTo("2 validation errors detected: "
                        + "Value '' at 'qualifier' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: \\$(LATEST(\\.PUBLISHED)?)|[a-zA-Z0-9-_$]+; "
                        + "Value '' at 'qualifier' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 1"));
    }

    @Test
    void longQualifierWithABadCharacterBreaksThePatternAndTheLength() {
        String qualifier = "a " + "a".repeat(127);
        given().body("{}").when().post(path(STREAMING, "?Qualifier=" + qualifier))
                .then().statusCode(400)
                .body("message", equalTo("2 validation errors detected: "
                        + "Value '" + qualifier + "' at 'qualifier' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: \\$(LATEST(\\.PUBLISHED)?)|[a-zA-Z0-9-_$]+; "
                        + "Value '" + qualifier + "' at 'qualifier' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 128"));
    }

    @Test
    void plainInvokeReportsTheSameQualifierViolations() {
        given().body("{}").when().post("/2015-03-31/functions/" + STREAMING + "/invocations?Qualifier=")
                .then().statusCode(400)
                .body("message", equalTo("2 validation errors detected: "
                        + "Value '' at 'qualifier' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: \\$(LATEST(\\.PUBLISHED)?)|[a-zA-Z0-9-_$]+; "
                        + "Value '' at 'qualifier' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 1"));
    }

    @Test
    void bodyThatIsNotJsonIsRefused() {
        given().body("not json").when().post(path(STREAMING, ""))
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestContentException"))
                .body("message", startsWith(
                        "Could not parse request body into json: Could not parse payload into json: "));
    }

    @Test
    void functionThatDoesNotExistIsNotFound() {
        given().body("{}").when().post(path("no-such-function-" + SUFFIX, ""))
                .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void missingFunctionIsReportedBeforeABadClientContext() {
        given().header("X-Amz-Client-Context", "%%%").body("{}")
                .when().post(path("no-such-function-" + SUFFIX, ""))
                .then().statusCode(404);

        given().header("X-Amz-Client-Context", "%%%").body("{}").when().post(path(STREAMING, ""))
                .then().statusCode(400).body("__type", equalTo("InvalidRequestContentException"));
    }

    @Test
    void emptyBodyAndJsonScalarAreAccepted() {
        invoke(STREAMING, "", "").then().statusCode(200);
        invoke(STREAMING, "3", "").then().statusCode(200);
    }

    private static Response invoke(String name, String body, String query) {
        return given().body(body).when().post(path(name, query));
    }

    private static String path(String name, String query) {
        return "/2021-11-15/functions/" + name + "/response-streaming-invocations" + query;
    }

    private static String chunks(List<Message> messages) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        messages.stream().filter(m -> "PayloadChunk".equals(m.type()))
                .forEach(m -> joined.writeBytes(m.payload()));
        return joined.toString(StandardCharsets.UTF_8);
    }

    private static void create(String name, String code) {
        given().contentType("application/json")
                .body("""
                        {
                          "FunctionName":"%s",
                          "Runtime":"nodejs22.x",
                          "Role":"arn:aws:iam::000000000000:role/lambda-role",
                          "Handler":"index.handler",
                          "Timeout":30,
                          "Code":{"ZipFile":"%s"}
                        }
                        """.formatted(name, Base64.getEncoder().encodeToString(LambdaCodeFixtures.nodeJsZip(code))))
                .when().post("/2015-03-31/functions")
                .then().statusCode(201);
    }
}
