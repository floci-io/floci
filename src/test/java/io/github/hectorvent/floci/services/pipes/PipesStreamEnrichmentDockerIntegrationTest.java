package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.DockerAvailability;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A table's stream through a real enrichment function onto an event bus: a row the Pipe's filter admits
 * becomes the two events the function answers, each with the source and detail type it named and its
 * serialized detail an object a rule can match on, as the Pipes pages describe.
 */
@QuarkusTest
class PipesStreamEnrichmentDockerIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/pipe-role";
    private static final Duration DEADLINE = Duration.ofSeconds(90);
    private static final String SQS_CONTENT_TYPE = "application/x-www-form-urlencoded";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void requireDocker() throws Exception {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(),
                "Docker daemon must be available for the enrichment function");
    }

    @Test
    void aRowBecomesTheEventsItsEnrichmentAnswers() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String table = "pipe-stream-" + suffix;
        String function = "pipe-enrich-" + suffix;
        String bus = "pipe-bus-" + suffix;
        String rule = "pipe-rule-" + suffix;
        String pipe = "pipe-stream-enrich-" + suffix;
        String matched = createQueue("pipe-matched-" + suffix);
        String deadLetters = createQueue("pipe-dlq-" + suffix);
        try {
            String streamArn = dynamoDb("CreateTable", """
                    {
                      "TableName": "%s",
                      "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "sk", "AttributeType": "S"}
                      ],
                      "KeySchema": [
                        {"AttributeName": "pk", "KeyType": "HASH"},
                        {"AttributeName": "sk", "KeyType": "RANGE"}
                      ],
                      "BillingMode": "PAY_PER_REQUEST",
                      "StreamSpecification": {"StreamEnabled": true, "StreamViewType": "NEW_AND_OLD_IMAGES"}
                    }
                    """.formatted(table));
            createFunction(function);
            events("CreateEventBus", "{\"Name\": \"%s\"}".formatted(bus));
            events("PutRule", """
                    {
                      "Name": "%s",
                      "EventBusName": "%s",
                      "EventPattern": "{\\"source\\":[\\"orders\\"],\\"detail\\":{\\"rowId\\":[\\"r1\\"]}}",
                      "State": "ENABLED"
                    }
                    """.formatted(rule, bus));
            events("PutTargets", """
                    {
                      "Rule": "%s",
                      "EventBusName": "%s",
                      "Targets": [{"Id": "1", "Arn": "%s"}]
                    }
                    """.formatted(rule, bus, queueArn(matched)));
            createPipe(pipe, streamArn, function, bus, queueArn(deadLetters));

            putItem(table, "EVENT#r1");
            putItem(table, "OTHER#r2");

            List<JsonNode> events = awaitMessages(matched, 2);

            for (JsonNode event : events) {
                assertEquals("orders", event.path("source").asText(), event.toString());
                assertEquals("Order Written", event.path("detail-type").asText(), event.toString());
                assertTrue(event.path("detail").isObject(), event.toString());
                assertEquals("r1", event.at("/detail/rowId").asText(), event.toString());
            }
            assertEquals(3, events.get(0).at("/detail/part").asInt() + events.get(1).at("/detail/part").asInt(),
                    "one event per entry the function answered");
            assertTrue(receive(matched).isEmpty(), "the filter keeps the other row out");
            assertTrue(receive(deadLetters).isEmpty(), "nothing failed");
        } finally {
            given().when().delete("/v1/pipes/" + pipe);
            given().when().delete("/2015-03-31/functions/" + function);
            deleteQueue(matched);
            deleteQueue(deadLetters);
        }
    }

    private static String createQueue(String name) {
        return given()
                .contentType(SQS_CONTENT_TYPE)
                .formParam("Action", "CreateQueue")
                .formParam("QueueName", name)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    private static String queueArn(String queueUrl) {
        return "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":" + queueUrl.substring(queueUrl.lastIndexOf('/') + 1);
    }

    private static void deleteQueue(String queueUrl) {
        given()
                .contentType(SQS_CONTENT_TYPE)
                .formParam("Action", "DeleteQueue")
                .formParam("QueueUrl", queueUrl)
        .when()
                .post("/");
    }

    /** At most one message's body, received without waiting and kept from coming back during the test. */
    private static String receive(String queueUrl) {
        return given()
                .contentType(SQS_CONTENT_TYPE)
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("MaxNumberOfMessages", "1")
                .formParam("VisibilityTimeout", "300")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().xmlPath().getString("ReceiveMessageResponse.ReceiveMessageResult.Message.Body");
    }

    private static List<JsonNode> awaitMessages(String queueUrl, int count) throws Exception {
        List<JsonNode> messages = new ArrayList<>();
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (messages.size() < count) {
            if (System.nanoTime() > deadline) {
                fail("no event on the queue a rule on the bus feeds within " + DEADLINE.toSeconds()
                        + " s: " + messages.size() + " of " + count + ": " + messages);
            }
            String body = receive(queueUrl);
            if (body.isEmpty()) {
                Thread.sleep(250);
            } else {
                messages.add(MAPPER.readTree(body));
            }
        }
        return messages;
    }

    private void createPipe(String pipe, String streamArn, String function, String bus, String deadLetterArn) {
        given()
                .contentType("application/json")
                .body("""
                    {
                      "Source": "%s",
                      "Enrichment": "arn:aws:lambda:%s:%s:function:%s",
                      "Target": "arn:aws:events:%s:%s:event-bus/%s",
                      "RoleArn": "%s",
                      "DesiredState": "RUNNING",
                      "SourceParameters": {
                        "DynamoDBStreamParameters": {
                          "StartingPosition": "TRIM_HORIZON",
                          "BatchSize": 10,
                          "DeadLetterConfig": {"Arn": "%s"}
                        },
                        "FilterCriteria": {
                          "Filters": [{"Pattern": "{\\"dynamodb\\":{\\"Keys\\":{\\"sk\\":{\\"S\\":[{\\"prefix\\":\\"EVENT#\\"}]}}}}"}]
                        }
                      },
                      "TargetParameters": {
                        "EventBridgeEventBusParameters": {"Source": "$.Source", "DetailType": "$.DetailType"},
                        "InputTemplate": "<$.Detail>"
                      }
                    }
                    """.formatted(streamArn, REGION, ACCOUNT, function, REGION, ACCOUNT, bus, ROLE, deadLetterArn))
        .when()
                .post("/v1/pipes/" + pipe)
        .then()
                .statusCode(200);
    }

    private void createFunction(String function) throws Exception {
        given()
                .contentType("application/json")
                .body("""
                    {
                      "FunctionName": "%s",
                      "Runtime": "nodejs20.x",
                      "Role": "%s",
                      "Handler": "index.handler",
                      "Timeout": 10,
                      "Code": {"ZipFile": "%s"}
                    }
                    """.formatted(function, ROLE, enrichmentZipBase64()))
        .when()
                .post("/2015-03-31/functions")
        .then()
                .statusCode(201);
    }

    private static String enrichmentZipBase64() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write("""
                    exports.handler = async (records) => records.flatMap((record) => {
                      const rowId = record.dynamodb.Keys.sk.S.split('#')[1];
                      return [1, 2].map((part) => ({
                        Source: 'orders',
                        DetailType: 'Order Written',
                        Detail: JSON.stringify({rowId, part}),
                      }));
                    });
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static void putItem(String table, String sortKey) {
        dynamoDb("PutItem", """
                {"TableName": "%s", "Item": {"pk": {"S": "orders"}, "sk": {"S": "%s"}}}
                """.formatted(table, sortKey));
    }

    private static String dynamoDb(String action, String body) {
        return given()
                .header("X-Amz-Target", "DynamoDB_20120810." + action)
                .contentType("application/x-amz-json-1.0")
                .body(body.getBytes(StandardCharsets.UTF_8))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().jsonPath().getString("TableDescription.LatestStreamArn");
    }

    private static void events(String action, String body) {
        given()
                .header("X-Amz-Target", "AWSEvents." + action)
                .contentType("application/x-amz-json-1.1")
                .body(body.getBytes(StandardCharsets.UTF_8))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }
}
