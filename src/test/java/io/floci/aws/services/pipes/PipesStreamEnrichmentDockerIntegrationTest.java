package io.floci.aws.services.pipes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.sqs.SqsService;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.DynamoDbRequests;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SqsTestQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    SqsService sqsService;

    // DynamoDB's requests and answers are application/x-amz-json-1.0, which RestAssured encodes and parses only once
    // told to, whichever classes ran before this one.
    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

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
        SqsTestQueue matched = SqsTestQueue.create(sqsService, ACCOUNT, REGION, "pipe-matched-");
        SqsTestQueue deadLetters = SqsTestQueue.create(sqsService, ACCOUNT, REGION, "pipe-dlq-");
        try {
            String streamArn = DynamoDbRequests.ddb("CreateTable", """
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
                    """.formatted(table)).statusCode(200).extract().jsonPath().getString("TableDescription.LatestStreamArn");
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
                    """.formatted(rule, bus, matched.arn()));
            createPipe(pipe, streamArn, function, bus, deadLetters.arn());

            putItem(table, "EVENT#r1");
            putItem(table, "OTHER#r2");

            List<JsonNode> events = List.of(matched.awaitJson(DEADLINE), matched.awaitJson(DEADLINE));

            for (JsonNode event : events) {
                assertEquals("orders", event.path("source").asText(), event.toString());
                assertEquals("Order Written", event.path("detail-type").asText(), event.toString());
                assertTrue(event.path("detail").isObject(), event.toString());
                assertEquals("r1", event.at("/detail/rowId").asText(), event.toString());
            }
            assertEquals(3, events.get(0).at("/detail/part").asInt() + events.get(1).at("/detail/part").asInt(),
                    "one event per entry the function answered");
            assertTrue(matched.receive().isEmpty(), "the filter keeps the other row out");
            assertTrue(deadLetters.receive().isEmpty(), "nothing failed");
        } finally {
            given().when().delete("/v1/pipes/" + pipe);
            given().when().delete("/2015-03-31/functions/" + function);
            matched.delete();
            deadLetters.delete();
        }
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
        DynamoDbRequests.ddb("PutItem", """
                {"TableName": "%s", "Item": {"pk": {"S": "orders"}, "sk": {"S": "%s"}}}
                """.formatted(table, sortKey)).statusCode(200);
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
